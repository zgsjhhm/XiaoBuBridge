#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v3.15 文生图取图链路的真机闭环验收（打设备上的 9876，不经过任何 Python 旁路）。

与离线回归的分工：`tools/run_image_codec_test.sh` 用真机抓到的 payload 文本证
「抽图逻辑对」（纯逻辑、秒级、无需设备）；本脚本证「整条链路通」——
请求 -> 注入生图意图 -> 引擎出图 -> 从 bean payload 抽 picUrl -> 下载 -> 转 OpenAI 契约。

它专门覆盖那个**只在默认 b64_json 下暴露**的上游时序缺陷：图片直链先于对象可读
被下发，载荷到达后立刻取会 404、数秒后才 200。首轮真机验证用的是
response_format=url（网关不下载），因此没踩到；默认 b64_json 必然踩到。所以
I1 断言的是「网关能把图真的交出来」，而不是「上游立刻可读」。

用法:
    python3 verify_image_device.py [port] [--key KEY] [--n-clamp]
    XBB_API_KEY=<key> python3 verify_image_device.py

设备侧端口映射（PC 上跑的话）:
    adb forward tcp:9876 tcp:9876
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

argv = [a for a in sys.argv[1:] if not a.startswith("--")]
flags = {a for a in sys.argv[1:] if a.startswith("--")}

PORT = int(argv[0]) if argv else 9876
BASE = f"http://127.0.0.1:{PORT}"
KEY = os.environ.get("XBB_API_KEY", "")
if "--key" in flags:
    idx = sys.argv.index("--key")
    KEY = sys.argv[idx + 1]
# --n-clamp：额外跑 n=99 的钳制用例（会串行出 4 张图，耗时 1 分钟级）
RUN_N_CLAMP = "--n-clamp" in flags

MAX_IMAGE_N = 4          # 与 OpenAIServer.MAX_IMAGE_N 对齐
PROMPT = "一只戴蓝色帽子的橘猫，扁平插画风"

fails = []


def check(cond, label, detail=""):
    print(("  PASS  " if cond else "  FAIL  ") + label
          + (("   | " + detail) if detail else ""), flush=True)
    if not cond:
        fails.append(label)


def _headers(extra=None):
    h = {"Content-Type": "application/json"}
    if KEY:
        h["Authorization"] = "Bearer " + KEY
    if extra:
        h.update(extra)
    return h


def post(payload, timeout=300):
    body = json.dumps(payload, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + "/v1/images/generations", data=body,
                                 headers=_headers())
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read()), time.time() - t0, r.status
    except urllib.error.HTTPError as e:
        raw = e.read() or b"{}"
        try:
            return json.loads(raw), time.time() - t0, e.code
        except Exception:
            return {"_raw": raw[:400].decode("utf-8", "replace")}, time.time() - t0, e.code


def sniff(data):
    """按魔数判图，不信 Content-Type —— 实测上游有 .jpg 直链却回 image/png 的情形。"""
    if data[:3] == b"\xff\xd8\xff":
        return "jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    return None


def i1_default_b64():
    print("\n[I1] 默认 response_format=b64_json 的完整闭环（本端点的默认路径）")
    d, dt, st = post({"prompt": PROMPT})
    check(st == 200, "HTTP 200（真机实测冷/暖态均为 200，耗时 8–35s）",
          f"{st} {dt:.1f}s")
    if st != 200:
        print(f"     {json.dumps(d, ensure_ascii=False)[:400]}", flush=True)
        return
    check(isinstance(d.get("created"), int), "created 为整数秒",
          str(d.get("created")))
    data = d.get("data") or []
    check(bool(data), "data 非空")
    if not data:
        return
    item = data[0]
    check("b64_json" in item, "item 带 b64_json（默认内联，响应自包含）")
    check("url" not in item,
          "b64_json 形态下不回吐 url（避免调用方以为拿到了托管地址）",
          str(list(item.keys())))
    check(item.get("revised_prompt") == PROMPT, "revised_prompt 原样回带 prompt",
          repr(item.get("revised_prompt"))[:80])

    b64 = item.get("b64_json") or ""
    raw = b""
    try:
        raw = base64.b64decode(b64, validate=True)
    except Exception as e:
        check(False, "b64_json 是合法 base64", str(e))
    if raw:
        kind = sniff(raw)
        check(kind in ("jpeg", "png", "gif", "webp"),
              "解码后是图片（按魔数判，不信 Content-Type）",
              f"{kind} {len(raw)}B")
        check(len(raw) > 1024, "图片体积合理（>1KB）", f"{len(raw)}B")
        print(f"     耗时 {dt:.1f}s（含等 CDN 回源的重试；离线代码层另有确定性回归）",
              flush=True)


def i2_url_format():
    print("\n[I2] response_format=url：只回上游直链，网关不下载")
    d, dt, st = post({"prompt": "一只戴红围巾的柴犬，水彩风", "response_format": "url"})
    check(st == 200, "HTTP 200", f"{st} {dt:.1f}s")
    if st != 200:
        return
    data = d.get("data") or []
    check(bool(data), "data 非空")
    if not data:
        return
    item = data[0]
    url = item.get("url") or ""
    check("b64_json" not in item, "url 形态下不带 b64_json", str(list(item.keys())))
    check(url.startswith("http://") or url.startswith("https://"), "url 是 http(s) 直链",
          url[:90])

    # 直链本身应可匿名取到（实测无需鉴权/Cookie）。CDN 可能尚未回源，故给几秒重试；
    # 这里失败只作提示，不算验收失败——url 契约只要求「把上游直链原样给出」。
    if url:
        ok = False
        for attempt in range(6):
            try:
                with urllib.request.urlopen(url, timeout=15) as r:
                    body = r.read()
                if r.status == 200 and body:
                    kind = sniff(body)
                    print(f"     PASS  直链匿名可取（attempt {attempt + 1}）: "
                          f"{r.status} {len(body)}B {kind} "
                          f"content-type={r.headers.get('Content-Type')}", flush=True)
                    ok = True
                    break
            except Exception as e:
                time.sleep(1.0)
        if not ok:
            print("     提示  直链 6 次内未取到（CDN 未回源或已过期）；"
                  "url 契约本身仍是「原样给出上游直链」，不计失败", flush=True)


def i3_bad_requests():
    print("\n[I3] 参数边界：缺 prompt / 非法 response_format 必须 400，且不得去注入")
    d, dt, st = post({})
    check(st == 400, "缺 prompt -> 400", f"{st} {dt:.2f}s")
    check((d.get("error") or {}).get("type") == "invalid_request_error",
          "错误体是 OpenAI 错误契约", str(d.get("error"))[:120])

    d, dt, st = post({"prompt": "猫", "response_format": "b64"})
    check(st == 400, "response_format=b64（拼写错误）-> 400，不静默当成默认",
          f"{st} {dt:.2f}s")
    msg = ((d.get("error") or {}).get("message") or "")
    check("b64_json" in msg and "url" in msg, "错误信息列出合法取值", msg[:120])


def i4_n_clamp():
    print(f"\n[I4] n 钳制：n=99 应被压到 {MAX_IMAGE_N}（小布一轮只出一张，串行）")
    d, dt, st = post({"prompt": "一只在窗台晒太阳的猫，扁平插画风", "n": 99})
    check(st == 200, "HTTP 200（超上限只钳制、不报错）", f"{st} {dt:.1f}s")
    if st != 200:
        print(f"     {json.dumps(d, ensure_ascii=False)[:300]}", flush=True)
        return
    n = len(d.get("data") or [])
    check(n <= MAX_IMAGE_N, f"返回张数 <= {MAX_IMAGE_N}", f"n={n}")


def main():
    print("=" * 72)
    print(f"v3.15 文生图取图 真机验收  ->  {BASE}   (n-clamp={RUN_N_CLAMP})")
    print("=" * 72)
    if KEY:
        print("（已带 API Key 鉴权头）")
    try:
        # 先确认网关在位，避免把「连不上」误报成一堆契约失败
        try:
            with urllib.request.urlopen(BASE + "/health", timeout=10) as r:
                health = json.loads(r.read())
            print(f"网关 version={health.get('version')} "
                  f"uptime={health.get('uptime_ms')}ms", flush=True)
        except Exception as e:
            check(False, "网关可达（/health）", str(e))
            print("\n网关不可达：确认设备已开机、模块作用域已勾选小布、"
                  "并已 `adb forward tcp:9876 tcp:9876`", flush=True)
            return 1

        i1_default_b64()
        i2_url_format()
        i3_bad_requests()
        if RUN_N_CLAMP:
            i4_n_clamp()
        else:
            print(f"\n[I4] n={MAX_IMAGE_N} 钳制用例已跳过（加 --n-clamp 运行，"
                  f"会串行出 {MAX_IMAGE_N} 张图）", flush=True)
    except Exception:
        import traceback
        traceback.print_exc()
        fails.append("异常终止")

    print("\n" + "=" * 72)
    if fails:
        print(f"失败 {len(fails)} 项:")
        for f in fails:
            print("  FAIL ", f)
        return 1
    print("全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
