"""验证生产 Summary 接口是否可成功调用 LLM。

示例：
    python scripts/check_summary_prod.py
    python scripts/check_summary_prod.py --uid <生产会话UID>

如需在 cloud-155 上绕过负载均衡、直连生产容器：
    python scripts/check_summary_prod.py --url http://127.0.0.1:20001/csp/keyword/summary
"""

import argparse
import json
import sys
import time
import traceback
import warnings

import requests
from urllib3.exceptions import InsecureRequestWarning


DEFAULT_URL = "https://ajx-summary.shsets.com/csp/keyword/summary"
DEFAULT_UID = "clegf62bd872-cacb-4d7f-8e1b-31511dcce9fd"


def main() -> int:
    parser = argparse.ArgumentParser(description="生产 Summary 接口诊断")
    parser.add_argument("--url", default=DEFAULT_URL, help="Summary 接口地址")
    parser.add_argument("--uid", default=DEFAULT_UID, help="有 ASR 文本的生产会话 UID")
    parser.add_argument("--timeout", type=float, default=45, help="请求超时秒数")
    parser.add_argument("--verify-ssl", action="store_true", help="校验入口 HTTPS 证书")
    args = parser.parse_args()

    if not args.verify_ssl:
        warnings.simplefilter("ignore", InsecureRequestWarning)

    print("=" * 72)
    print("生产 Summary / LLM 连通性验证")
    print(f"地址: {args.url}")
    print(f"UID: {args.uid}")
    print("=" * 72)

    started = time.perf_counter()
    try:
        response = requests.post(
            args.url,
            headers={"Content-Type": "application/json"},
            json={"uid": args.uid},
            timeout=args.timeout,
            verify=args.verify_ssl,
        )
        elapsed = time.perf_counter() - started
        print(f"HTTP 状态: {response.status_code}")
        print(f"耗时: {elapsed:.3f}s")
        try:
            body = response.json()
            print(json.dumps(body, ensure_ascii=False, indent=2))
            return 0 if response.ok and body.get("statusCode") == "200" else 1
        except ValueError:
            print(response.text)
            return 1
    except Exception as error:
        print(f"请求异常（{time.perf_counter() - started:.3f}s）: {error}")
        traceback.print_exc()
        return 2


if __name__ == "__main__":
    sys.exit(main())
