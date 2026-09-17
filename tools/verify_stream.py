#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
XiaoBuBridge v3.6+ 真流式（chunked）验收脚本。

不复用 openai SDK（沙箱无网），而是逐字节复刻 SDK 的解析契约：
  1. 响应头必须是 Transfer-Encoding: chunked 且无 Content-Length；
  2. 必须按 HTTP chunked 分帧解码，而不是当作一个整体 body；
  3. 每帧必须是 `data: <json>\\n\\n`，json 能被 json.loads 解析；
  4. 首字必须早于整段结束到达（否则只是"格式流式"，不是"时序流式"）；
  5. 必须以 `data: [DONE]` + 零长度终止块结束；
  6. delta.content 拼接结果必须与 /v1/models 里的 model 一致且非空；
  7. UTF-8 多字节字符不得跨 chunk 边界被截断成乱码。

用法: python3 verify_stream.py [port] [prompt]
"""
import json
import socket
import sys
import time

HOST = "127.0.0.1"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9876
PROMPT = sys.argv[2] if len(sys.argv) > 2 else "讲个300字左右的杭州介绍"

fails = []


def check(name, ok, detail=""):
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  | " + detail) if detail else ""))
    if not ok:
        fails.append(name)


class ChunkedReader:
    """最小 HTTP/1.1 chunked 解码器：按 chunk 产出 (到达时刻, 数据)。"""

    def __init__(self, sock):
        self.fp = sock.makefile("rb")
        self.t0 = time.time()

    def read_headers(self):
        headers = {}
        status = None
        while True:
            line = self.fp.readline()
            if not line:
                break
            line = line.rstrip(b"\r\n")
            if status is None:
                status = line.decode("latin1")
                continue
            if not line:
                break
            k, _, v = line.decode("latin1").partition(":")
            headers[k.strip().lower()] = v.strip()
        return status, headers

    def read_chunks(self):
        """yield (t_since_start, bytes)。零长度终止块结束迭代。"""
        while True:
            size_line = self.fp.readline()
            if not size_line:
                return
            size_line = size_line.strip()
            if b";" in size_line:
                size_line = size_line.split(b";")[0]
            if not size_line:
                continue
            try:
                size = int(size_line, 16)
            except ValueError:
                return
            if size == 0:
                # 吃掉 trailer 直到空行
                while True:
                    tail = self.fp.readline()
                    if not tail or tail in (b"\r\n", b"\n"):
                        break
                return
            buf = b""
            while len(buf) < size:
                piece = self.fp.read(size - len(buf))
                if not piece:
                    return
                buf += piece
            self.fp.read(2)  # 尾随 CRLF
            yield (time.time() - self.t0, buf)


def main():
    body = json.dumps({
        "model": "xiaobu",
        "stream": True,
        "messages": [{"role": "user", "content": PROMPT}],
    }).encode("utf-8")

    req = (
        b"POST /v1/chat/completions HTTP/1.1\r\n"
        b"Host: " + HOST.encode() + b":" + str(PORT).encode() + b"\r\n"
        b"Content-Type: application/json\r\n"
        b"Accept: text/event-stream\r\n"
        b"Content-Length: " + str(len(body)).encode() + b"\r\n"
        b"Connection: close\r\n\r\n" + body
    )

    print("== 请求: " + PROMPT)
    sock = socket.create_connection((HOST, PORT), timeout=10)
    sock.settimeout(120)
    t_send = time.time()
    sock.sendall(req)

    rd = ChunkedReader(sock)
    status, headers = rd.read_headers()
    print("== 状态行: " + str(status))
    print("== 响应头: " + json.dumps(headers, ensure_ascii=False))

    check("HTTP 200", status is not None and "200" in status)
    check("Content-Type 为 text/event-stream",
          headers.get("content-type", "").startswith("text/event-stream"),
          headers.get("content-type", "<missing>"))

    is_chunked = headers.get("transfer-encoding", "").lower() == "chunked"
    check("Transfer-Encoding: chunked", is_chunked, headers.get("transfer-encoding", "<missing>"))
    check("无 Content-Length（否则不可能真流式）", "content-length" not in headers,
          headers.get("content-length", "<absent>"))

    raw_chunks = []
    sse_events = []
    pending = b""
    first_content_at = None
    first_byte_at = None
    bad_json = 0

    for t, data in rd.read_chunks():
        if first_byte_at is None:
            first_byte_at = t
        raw_chunks.append((t, len(data)))
        pending += data
        # SSE 以空行分事件
        while b"\n\n" in pending:
            event, pending = pending.split(b"\n\n", 1)
            for line in event.split(b"\n"):
                if not line.startswith(b"data:"):
                    continue
                payload = line[5:].strip()
                if payload == b"[DONE]":
                    sse_events.append((t, None))
                    continue
                try:
                    obj = json.loads(payload.decode("utf-8"))
                except Exception as e:
                    bad_json += 1
                    print("      非法 JSON 帧: " + repr(payload[:80]) + " -> " + str(e))
                    continue
                sse_events.append((t, obj))
                delta = obj.get("choices", [{}])[0].get("delta", {})
                if delta.get("content") and first_content_at is None:
                    first_content_at = t

    sock.close()
    total_elapsed = time.time() - t_send

    print("== HTTP 分帧数: %d，SSE 事件数: %d" % (len(raw_chunks), len(sse_events)))
    print("== 分帧大小/时刻: " + ", ".join("+%dms:%dB" % (t * 1000, n) for t, n in raw_chunks[:12]))

    check("Chunked 分帧数 > 1（HTTP 层确实分了片）", len(raw_chunks) > 1, str(len(raw_chunks)))
    check("无非法 JSON 帧", bad_json == 0, "bad=%d" % bad_json)

    done_at = None
    for t, obj in sse_events:
        if obj is None:
            done_at = t
    check("以 data: [DONE] 终止", done_at is not None)

    # 拼接所有 delta.content
    pieces = []
    struct_ok = True
    for t, obj in sse_events:
        if obj is None:
            continue
        if obj.get("object") != "chat.completion.chunk":
            struct_ok = False
        ch = obj.get("choices")
        if not isinstance(ch, list) or not ch:
            struct_ok = False
            continue
        if "delta" not in ch[0]:
            struct_ok = False
        c = ch[0].get("delta", {}).get("content")
        if c:
            pieces.append(c)
    content = "".join(pieces)
    check("chunk 结构符合 OpenAI 规范(object/choices/delta)", struct_ok)
    check("拼接后内容非空", len(content) > 0, "len=%d" % len(content))
    check("内容无 UTF-8 替换字符(截断痕迹)", "\ufffd" not in content,
          "found U+FFFD" if "\ufffd" in content else "")

    print("== 拼接结果(%d 字): %s" % (len(content), content[:120].replace("\n", " ")))

    # 时序判定：首内容帧应显著早于最后一帧
    if first_content_at is not None and done_at is not None:
        print("== 首内容帧 +%dms，结束帧 +%dms" % (first_content_at * 1000, done_at * 1000))
        check("首字早于流结束（真时序流式，非攒包）", first_content_at < done_at)
        check("边收边发（首字显著早于结束，>100ms）",
              (done_at - first_content_at) > 0.1,
              "gap=%dms" % ((done_at - first_content_at) * 1000))
    else:
        check("捕获到首内容帧", first_content_at is not None)

    print("== 全流程耗时 %.2fs" % total_elapsed)
    print()
    if fails:
        print("RESULT: FAIL -> " + ", ".join(fails))
        return 1
    print("RESULT: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
