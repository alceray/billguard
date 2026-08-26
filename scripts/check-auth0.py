#!/usr/bin/env python3
"""Validate an Auth0 tenant against what BillGuard needs.

Runs three unauthenticated probes against /authorize and reports which half of
the configuration is wrong. Requires no Auth0 credentials and changes nothing.

    python scripts/check-auth0.py
"""

import base64
import hashlib
import os
import re
import subprocess
import sys
import urllib.parse

REDIRECT_URI = "http://localhost:5173/callback"
ENV_FILE = "web-app/.env.local"
KEYS = ("VITE_AUTH0_DOMAIN", "VITE_AUTH0_CLIENT_ID", "VITE_AUTH0_AUDIENCE")


def read_env(path):
    values = {}
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                if "=" in line and not line.lstrip().startswith("#"):
                    key, value = line.split("=", 1)
                    values[key.strip()] = value.strip()
    except FileNotFoundError:
        sys.exit("Missing %s. Copy web-app/.env.example to it first." % path)
    missing = [key for key in KEYS if not values.get(key)]
    if missing:
        sys.exit("%s is missing values for: %s" % (path, ", ".join(missing)))
    return values


def authorize(domain, client_id, audience):
    """Return (error_description, location) for one /authorize attempt."""
    verifier = base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip("=")
    challenge = (
        base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest())
        .decode()
        .rstrip("=")
    )
    params = {
        "client_id": client_id,
        "redirect_uri": REDIRECT_URI,
        "scope": "openid profile email",
        "response_type": "code",
        "response_mode": "query",
        "state": "probe",
        "nonce": "probe",
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    }
    if audience:
        params["audience"] = audience
    url = "https://%s/authorize?%s" % (domain, urllib.parse.urlencode(params))
    result = subprocess.run(
        ["curl", "-s", "-i", "-o", "-", url], capture_output=True, text=True
    ).stdout
    match = re.search(r"^location:\s*(.+)$", result, re.I | re.M)
    if not match:
        return "no redirect returned", None
    location = match.group(1).strip()
    query = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)
    if "error_description" in query:
        return urllib.parse.unquote(query["error_description"][0]), location
    return None, location


def main():
    env = read_env(ENV_FILE)
    domain = env["VITE_AUTH0_DOMAIN"]
    client_id = env["VITE_AUTH0_CLIENT_ID"]
    audience = env["VITE_AUTH0_AUDIENCE"]

    print("domain    %s" % domain)
    print("client_id %s" % client_id)
    print("audience  %s\n" % audience)

    client_error, _ = authorize(domain, client_id, None)
    api_error, _ = authorize(domain, client_id, audience)
    _, bogus_location = authorize(domain, client_id, "https://probe.invalid/api")

    if client_error:
        print("FAIL  application")
        print("      %s" % client_error)
        print("      The client id is wrong, the application is disabled, or")
        print("      %s is not an allowed callback URL." % REDIRECT_URI)
        return 1

    print("OK    application accepts a browser login and the callback URL")

    if not api_error:
        print("OK    API grant resolves")
        print("\nAuth0 is configured correctly.")
        return 0

    print("FAIL  API")
    print("      %s" % api_error)
    if "not found" in api_error.lower():
        print("      No API in this tenant has that Identifier. Check for a")
        print("      trailing slash, and confirm the tenant in the dashboard.")
    else:
        print("      The API exists but this client cannot reach it. A")
        print("      first-party Single Page Application needs no grant, so")
        print("      the application is likely not first-party or not a SPA.")
        print("      Recreating it as a Single Page Web Application is the")
        print("      fastest fix; recreate the API next if that does not help.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
