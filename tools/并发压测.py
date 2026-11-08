# -*- coding: utf-8 -*-
"""Fish Audio 并发上限压测（带 model 头，走本机代理）"""
import argparse
import concurrent.futures
import time
import requests

KEY = "sk-fish-mtltFcL1OTNneQHyIFeri-fnbHj-e1x4W9GpKpAX-Jw"
API = "https://api.fish.audio/v1/tts"
PROXIES = {"https": "http://127.0.0.1:7897", "http": "http://127.0.0.1:7897"}
TEXT = "这是一段用于测试并发上限的文本，长度适中，不涉及任何具体内容。"


def one_request(i, model):
    t0 = time.time()
    try:
        r = requests.post(
            API,
            json={"text": TEXT, "format": "mp3"},
            headers={"Authorization": "Bearer " + KEY, "model": model},
            proxies=PROXIES, timeout=90)
        dt = time.time() - t0
        if r.status_code == 200 and len(r.content) > 1024:
            return ("ok", dt)
        return ("http%d" % r.status_code, dt)
    except Exception as exc:
        return ("err:" + type(exc).__name__, time.time() - t0)


def wave(model, n):
    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=n) as ex:
        results = list(ex.map(lambda i: one_request(i, model), range(n)))
    wall = time.time() - t0
    ok = sum(1 for r in results if r[0] == "ok")
    other = {}
    for status, _ in results:
        if status != "ok":
            other[status] = other.get(status, 0) + 1
    times = sorted(dt for s, dt in results if s == "ok")
    med = times[len(times) // 2] if times else 0.0
    p90 = times[int(len(times) * 0.9)] if times else 0.0
    return ok, other, med, p90, wall


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="s2.1-pro-free")
    ap.add_argument("--levels", default="100,50,20,10,5")
    args = ap.parse_args()
    print("model=%s levels=%s" % (args.model, args.levels))
    for lv in [int(x) for x in args.levels.split(",")]:
        ok, other, med, p90, wall = wave(args.model, lv)
        print("并发=%3d  成功=%3d  失败=%s  成功中位=%.1fs p90=%.1fs  整波=%.1fs"
              % (lv, ok, other or "-", med, p90, wall), flush=True)
        time.sleep(10)


if __name__ == "__main__":
    main()
