#!/usr/bin/env python3
import datetime
import json

import requests

# TODO: convert to real test, this is just a quick llm-assisted sanity check.

BASE_URL = "http://localhost:8080"
API_KEY = "v4v4me"


def timestamp():
    return datetime.datetime.now(datetime.timezone.utc)


def test_boost(message_size_kb, should_fail=False):
    """
    Test posting a boost with a message of specified size.

    Args:
        message_size_kb: Size of message in kilobytes
        should_fail: If True, we expect this to fail (for testing limits)
    """
    message = "a" * (message_size_kb * 1024)

    payload = {
        "action": "boost",
        "split": 0.5,
        "value_msat": 1000,
        "value_msat_total": 2000,
        "timestamp": timestamp().isoformat(),
        "message": message,
    }

    headers = {"x-api-key": f"{API_KEY}", "Content-Type": "application/json"}

    payload_size_kb = len(json.dumps(payload)) / 1024

    print(f"\n{'=' * 60}")
    print(f"Testing with message size: {message_size_kb}KB")
    print(f"Total payload size: {payload_size_kb:.2f}KB")
    print(f"Expected: {'FAIL' if should_fail else 'PASS'}")
    print(f"{'=' * 60}")

    try:
        resp = requests.post(
            f"{BASE_URL}/boost", json=payload, headers=headers, timeout=5
        )

        print(f"Status: {resp.status_code}")
        print(f"Response: {resp.text}")

        if should_fail and resp.status_code >= 400:
            print("✓ Failed as expected")
            return True
        elif not should_fail and resp.status_code == 201:
            print("✓ Passed as expected")
            return True
        else:
            print("✗ Unexpected result")
            return False

    except requests.exceptions.RequestException as e:
        print(f"✗ Request failed: {e}")
        return False


if __name__ == "__main__":
    # Test cases
    print("BoostBox Size Limit Tests")
    print(f"Base URL: {BASE_URL}")
    print(f"API Key: {API_KEY}")

    # Test under limit
    test_boost(50, should_fail=False)

    # Test near limit
    test_boost(95, should_fail=False)

    # Test over limit
    test_boost(150, should_fail=True)
