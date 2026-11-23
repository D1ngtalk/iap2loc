#!/usr/bin/env python3
"""Decode optional CI secrets into a temporary Android assets directory; never print material."""
import base64
import os
from pathlib import Path
import subprocess
import sys


def openssl(*args, data=b""):
    result = subprocess.run(
        ["openssl", *args], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    if result.returncode:
        raise ValueError("MFi 文件无法解析，请检查未加密的 PKCS#8 / SEC1 DER 私钥及 PKCS#7 DER 证书链")
    return result.stdout


def main():
    key = os.environ.get("MFI_IDENTITY_PK8_BASE64", "").strip()
    certificate = os.environ.get("MFI_CERTIFICATE_P7B_BASE64", "").strip()
    if not key and not certificate:
        print("未配置 MFi Secrets，构建需手动导入身份的普通版")
        return
    if not key or not certificate:
        raise ValueError("MFI_IDENTITY_PK8_BASE64 与 MFI_CERTIFICATE_P7B_BASE64 必须同时配置")

    key_bytes = base64.b64decode("".join(key.split()), validate=True)
    certificate_bytes = base64.b64decode("".join(certificate.split()), validate=True)
    # OpenSSL pkey accepts SEC1 as well as PKCS#8, while the app's PKCS8EncodedKeySpec does not.
    # Normalize in memory before validating or publishing any material. An empty passphrase
    # prevents OpenSSL from prompting for encrypted input; no key-password secret is supported.
    key_bytes = openssl(
        "pkcs8", "-topk8", "-nocrypt", "-inform", "DER", "-outform", "DER",
        "-passin", "pass:", data=key_bytes,
    )
    description = openssl("pkey", "-inform", "DER", "-text", "-noout", data=key_bytes)
    if b"ASN1 OID: prime256v1" not in description:
        raise ValueError("MFi 私钥必须使用 P-256 曲线")
    public_key = openssl("pkey", "-inform", "DER", "-pubout", "-outform", "DER", data=key_bytes)
    chain = openssl("pkcs7", "-inform", "DER", "-print_certs", data=certificate_bytes)
    # A PKCS#7 chain need not put the accessory certificate first.
    matched = False
    for part in chain.split(b"-----BEGIN CERTIFICATE-----")[1:]:
        pem = b"-----BEGIN CERTIFICATE-----" + part.split(b"-----END CERTIFICATE-----")[0] + b"-----END CERTIFICATE-----\n"
        cert_public = openssl("x509", "-pubkey", "-noout", data=pem)
        matched |= public_key == openssl("pkey", "-pubin", "-outform", "DER", data=cert_public)
    if not matched:
        raise ValueError("MFi 私钥与证书链不匹配")

    root = Path(os.environ["RUNNER_TEMP"]) / "iap2loc-mfi-assets"
    directory = root / "offline-mfi"
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    for name, material in (("identity.pk8", key_bytes), ("certificate.p7b", certificate_bytes)):
        path = directory / name
        with path.open("wb") as output:
            os.chmod(path, 0o600)
            output.write(material)

    with Path(os.environ["GITHUB_ENV"]).open("a") as output:
        output.write(f"IAP2LOC_MFI_ASSETS={root}\n")
    print("MFi 身份格式与配对检查通过，将打入 Release APK")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
