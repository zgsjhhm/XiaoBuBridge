#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v3.8 工具调用内嵌层的真机闭环验收（打设备上的 9876，不经过任何 Python 旁路）。

与离线回归的分工：离线证「代码逻辑对」，本脚本证「整条链路通」——
带 tools 请求 -> 网关折叠注入 -> 小布按 XML 协议输出 -> 网关解析成
标准 tool_calls -> 本地执行工具 -> 回传 role=tool -> 拿最终 stop 回答。
这是任何 function calling 客户端（LangChain / OpenAI SDK / Cherry Studio）
实际会做的事。

用法: python3 verify_toolcall_device.py [port]
"""
import json
import sys
import time
import urllib.error
import urllib.request

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9876
BASE = f"http://127.0.0.1:{PORT}"

TOOLS = [
    {"type": "function", "function": {
        "name": "read_file", "description": "读取本地文本文件内容。",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string", "description": "文件绝对路径"}},
            "required": ["path"]}}},
    {"type": "function", "function": {
        "name": "get_weather", "description": "查询城市天气。",
        "parameters": {"type": "object", "properties": {
            "city": {"type": "string", "description": "城市名"}},
            "required": ["city"]}}},
]

fails = []


def check(cond, label, detail=""):
    print(("  PASS  " if cond else "  FAIL  ") + label
          + (("   | " + detail) if detail else ""), flush=True)
    if not cond:
        fails.append(label)


def post(payload, timeout=300):
    body = json.dumps(payload, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + "/v1/chat/completions", data=body,
                                 headers={"Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read()), time.time() - t0, r.status
    except urllib.error.HTTPError as e:
        return json.loads(e.read() or b"{}"), time.time() - t0, e.code


def validate_shape(msg, label):
    """校验 tool_calls 符合 OpenAI 规范（客户端会做这些校验）。"""
    tcs = msg.get("tool_calls") or []
    check(bool(tcs), f"{label}：返回了 tool_calls")
    if not tcs:
        return []
    check(all(str(t.get("id", "")).startswith("call_") for t in tcs),
          f"{label}：id 前缀为 call_（SDK 会校验）",
          str([t.get("id") for t in tcs]))
    check(all(t.get("type") == "function" for t in tcs), f"{label}：type=function")
    check(all(t.get("function", {}).get("name") for t in tcs),
          f"{label}：function.name 非空")
    args_ok = True
    for t in tcs:
        a = t["function"].get("arguments")
        if isinstance(a, str):
            try:
                json.loads(a)
            except Exception:
                args_ok = False
        else:
            args_ok = False
    check(args_ok, f"{label}：arguments 是可解析的 JSON 字符串",
          str([t["function"].get("arguments") for t in tcs]))
    return tcs


def e2e_closed_loop():
    print("\n[E0] 完整闭环：请求 -> tool_calls -> 执行 -> 回传 -> stop")
    msgs = [{"role": "user",
             "content": "读取 /etc/hostname 这个文件的内容。必须真实调用工具。"}]
    d, dt, st = post({"model": "xiaobu", "messages": msgs, "tools": TOOLS})
    check(st == 200, "HTTP 200", f"{st} {dt:.1f}s")
    if st != 200:
        print(f"     {json.dumps(d, ensure_ascii=False)[:400]}", flush=True)
        return
    ch = d["choices"][0]
    check(ch.get("finish_reason") == "tool_calls",
          "finish_reason=tool_calls", f"-> {ch.get('finish_reason')}")
    tcs = validate_shape(ch["message"], "E0")
    if not tcs:
        print(f"     content: {(ch['message'].get('content') or '')[:300]!r}", flush=True)
        return
    print(f"     调用: {tcs[0]['function']['name']} "
          f"{tcs[0]['function']['arguments']}", flush=True)

    # 本地真执行工具（网关不回执行，这是客户端的活）
    args = json.loads(tcs[0]["function"]["arguments"])
    try:
        content = open(args.get("path", "")).read()[:200]
        result = f"{content}\n(文件共 {len(content)} 字符)"
    except Exception as e:
        result = f"读取失败：{e}"

    msgs.append({"role": "assistant", "content": ch["message"].get("content"),
                 "tool_calls": tcs})
    msgs.append({"role": "tool", "tool_call_id": tcs[0]["id"], "content": result})
    msgs.append({"role": "user", "content": "基于上面的结果回答：文件里第一行是什么？"})

    d2, dt2, st2 = post({"model": "xiaobu", "messages": msgs, "tools": TOOLS})
    check(st2 == 200, "第二轮 HTTP 200", f"{st2} {dt2:.1f}s")
    if st2 != 200:
        return
    ch2 = d2["choices"][0]
    final = ch2["message"].get("content") or ""
    check(ch2.get("finish_reason") == "stop",
          "最终 finish_reason=stop", f"-> {ch2.get('finish_reason')}")
    check(bool(final.strip()), "最终有文本回答")
    print(f"     最终回答: {final.strip()[:200]!r}", flush=True)


def e1_choice_none():
    print("\n[E1] tool_choice='none' 必须收不到 tool_calls")
    d, dt, st = post({"model": "xiaobu", "tools": TOOLS, "tool_choice": "none",
                      "messages": [{"role": "user",
                                    "content": "读取 /etc/hostname。必须调用工具。"}]})
    check(st == 200, "HTTP 200", f"{dt:.1f}s")
    if st != 200:
        return
    ch = d["choices"][0]
    tcs = ch["message"].get("tool_calls") or []
    check(not tcs, "未返回 tool_calls（契约生效）",
          str([t["function"]["name"] for t in tcs]))
    check(ch.get("finish_reason") == "stop", "finish_reason=stop",
          f"-> {ch.get('finish_reason')}")


def e2_forced_choice():
    print("\n[E2] tool_choice 对象点名 get_weather")
    d, dt, st = post({"model": "xiaobu", "tools": TOOLS,
                      "tool_choice": {"type": "function",
                                      "function": {"name": "get_weather"}},
                      "messages": [{"role": "user", "content": "杭州今天天气怎么样？"}]})
    check(st == 200, "HTTP 200", f"{dt:.1f}s")
    if st != 200:
        return
    ch = d["choices"][0]
    tcs = ch["message"].get("tool_calls") or []
    if tcs:
        names = [t["function"]["name"] for t in tcs]
        check("get_weather" in names, "点名生效，调了 get_weather", str(names))
        print(f"     参数: {tcs[0]['function']['arguments']}", flush=True)
    else:
        check(False, "点名生效，调了 get_weather",
              f"未调用，content={(ch['message'].get('content') or '')[:150]!r}")


def e3_no_tools():
    print("\n[E3] 无 tools 的纯聊天不得产生 tool_calls（不能回归）")
    d, dt, st = post({"model": "xiaobu",
                      "messages": [{"role": "user", "content": "用一句话解释递归。"}]})
    check(st == 200 and not (d["choices"][0]["message"].get("tool_calls")),
          "无 tools 时不产生 tool_calls", f"{dt:.1f}s")
    txt = d["choices"][0]["message"].get("content") or ""
    check(bool(txt.strip()), "纯聊天仍有正常文本回答")
    print(f"     回答: {txt.strip()[:120]!r}", flush=True)


def e4_stream():
    print("\n[E4] 流式（stream=true）下的 tool_calls 契约")
    payload = {"model": "xiaobu", "tools": TOOLS, "stream": True,
               "messages": [{"role": "user",
                             "content": "读取 /etc/hostname。必须调用工具。"}]}
    body = json.dumps(payload, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + "/v1/chat/completions", data=body,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            raw = r.read().decode("utf-8", "replace")
    except Exception as e:
        check(False, "SSE 可读", str(e))
        return
    check("data: [DONE]" in raw, "SSE 以 [DONE] 收尾")
    check("tool_calls" in raw, "SSE 里带 tool_calls 分片")
    check('"finish_reason": "tool_calls"' in raw
          or '"finish_reason":"tool_calls"' in raw,
          "SSE 的 finish_reason=tool_calls")
    check('"index": 0' in raw or '"index":0' in raw,
          "分片带 index（OpenAI 规范）")
    if fails:
        print(f"     SSE 片段: {raw[:400]!r}", flush=True)


def main():
    print("=" * 70)
    print(f"v3.8 工具调用内嵌层 真机验收  ->  {BASE}")
    print("=" * 70)
    which = sys.argv[2] if len(sys.argv) > 2 else "all"
    try:
        if which in ("all", "e0"):
            e2e_closed_loop()
        if which in ("all", "e1"):
            e1_choice_none()
        if which in ("all", "e2"):
            e2_forced_choice()
        if which in ("all", "e3"):
            e3_no_tools()
        if which in ("all", "e4"):
            e4_stream()
    except Exception as e:
        import traceback
        traceback.print_exc()
        fails.append(f"异常: {e}")

    print("\n" + "=" * 70)
    if fails:
        print(f"失败 {len(fails)} 项:")
        for f in fails:
            print("  FAIL ", f)
        return 1
    print("全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
