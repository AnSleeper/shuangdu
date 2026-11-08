# -*- coding: utf-8 -*-
"""Fish Audio 并发上限压测

用法（在 PC 上跑，不要用 app 压测）：
  python 并发压测.py --key sk-fish-xxxx --voice <voice_id> [--model s2.1-pro-free]

从并发 100 开始逐级降（100→50→20→10→5），每一级同时发出 N 个合成请求，
统计成功 / 429 限流 / 其他失败的数量和耗时，用于确定安全的并发上限。
"""
import argparse
import concurrent.futures
import json
import time
import urllib.request
import urllib.error

API = "https://api.fish.audio/v1/tts"

TEXT = "这是一段用于测试并发上限的文本，长度适中，不代表任何立场。"


def one_request(key, voice, model, payload):
    body = json.dumps({"text": payload, "reference_id": voice, "format": "mp3"}).encode("utf-8")
    req = urllib.request.Request(API, data=body, method="POST")
    req.add_header("Authorization", "Bearer " + key)
    req.add_header("Content-Type", "application/json")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
            ok = len(data) > 1024
            return ("ok" if ok else "empty", time.time() - t0, r.status)
    except urllib.error.HTTPError as e:
        if e.code == 429:
            return ("429", time.time() - t0, 429)
        return ("http%d" % e.code, time.time() - t0, e.code)
    except Exception as exc:  # noqa
        return ("err:" + str(exc)[:40], time.time() - t0, 0)


def wave(key, voice, model, n):
    results = []
    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=n) as ex:
        futs = [ex.submit(one_request, key, voice, model, TEXT) for _ in range(n)]
        for f in futs:
            results.append(f.result())
    wall = time.time() - t0
    ok = sum(1 for r in results if r[0] == "ok")
    rl = sum(1 for r in results if r[0] == "429")
    other = n - ok - rl
    times = sorted(r[1] for r in results if r[0] == "ok")
    med = times[len(times) // 2] if times else 0
    return ok, rl, other, med, wall


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--key", required=True)
    ap.add_argument("--voice", default="")
    ap.add_argument("--model", default="s2.1-pro-free")
    ap.add_argument("--levels", default="100,50,20,10,5")
    args = ap.parse_args()

    print("model=%s levels=%s" % (args.model, args.levels))
    for lv in [int(x) for x in args.levels.split(",")]:
        ok, rl, other, med, wall = wave(args.key, args.voice, args.model, lv)
        print("并发=%3d  成功=%3d  429=%3d  其他失败=%2d  成功中位耗时=%.1fs  整波=%.1fs"
              % (lv, ok, rl, other, med, wall))
        time.sleep(10)  # 档位之间歇一歇


if __name__ == "__main__":
    main()
