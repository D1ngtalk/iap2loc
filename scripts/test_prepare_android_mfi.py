"""Regression tests using generated, disposable identities; no real secrets are needed."""
import base64
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("prepare-android-mfi.py")


def run(*args, data=b""):
    return subprocess.run(args, input=data, capture_output=True, check=True, timeout=20).stdout


class PrepareAndroidMfiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixtures = tempfile.TemporaryDirectory(prefix="iap2loc-mfi-fixtures-")
        cls.addClassCleanup(cls.fixtures.cleanup)
        cls.root = Path(cls.fixtures.name)
        cls.identities = {}
        for name, curve in (("accessory", "prime256v1"), ("other", "prime256v1"), ("wrong-curve", "secp384r1")):
            pem = cls.root / f"{name}.pem"
            certificate = cls.root / f"{name}.crt"
            run("openssl", "ecparam", "-name", curve, "-genkey", "-noout", "-out", str(pem))
            run("openssl", "req", "-new", "-x509", "-key", str(pem), "-subj", f"/CN={name}",
                "-days", "1", "-out", str(certificate))
            cls.identities[name] = {
                "pem": pem.read_bytes(),
                "sec1": run("openssl", "ec", "-in", str(pem), "-outform", "DER"),
                "pkcs8": run("openssl", "pkcs8", "-topk8", "-nocrypt", "-in", str(pem), "-outform", "DER"),
                "certificate": run("openssl", "crl2pkcs7", "-nocrl", "-certfile", str(certificate), "-outform", "DER"),
                "public": run("openssl", "pkey", "-in", str(pem), "-pubout", "-outform", "DER"),
            }

        # Use the same JCA key parser as MfiSigner, then sign with the output and verify against
        # the original key's public part. OpenSSL parsing alone would miss the SEC1 regression.
        verifier = cls.root / "VerifyPkcs8.java"
        verifier.write_text("""
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.*;
public class VerifyPkcs8 {
    public static void main(String[] args) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("EC");
        ECPrivateKey key = (ECPrivateKey) factory.generatePrivate(
            new PKCS8EncodedKeySpec(Files.readAllBytes(Path.of(args[0]))));
        if (key.getParams().getCurve().getField().getFieldSize() != 256)
            throw new AssertionError("not P-256");
        PublicKey publicKey = factory.generatePublic(
            new X509EncodedKeySpec(Files.readAllBytes(Path.of(args[1]))));
        byte[] message = new byte[32];
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(message);
        byte[] signed = signature.sign();
        signature.initVerify(publicKey);
        signature.update(message);
        if (!signature.verify(signed)) throw new AssertionError("key changed");
    }
}
""", encoding="utf-8")
        run("javac", str(verifier))

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="iap2loc-mfi-test-")
        self.addCleanup(temporary.cleanup)
        self.work = Path(temporary.name)
        self.invocations = 0

    def prepare(self, key=None, certificate=None, secrets=None):
        self.invocations += 1
        runner = self.work / str(self.invocations)
        runner.mkdir()
        environment_file = runner / "github-env"
        env = os.environ.copy()
        for name in ("MFI_IDENTITY_PK8_BASE64", "MFI_CERTIFICATE_P7B_BASE64", "IAP2LOC_MFI_ASSETS"):
            env.pop(name, None)
        env.update(RUNNER_TEMP=str(runner), GITHUB_ENV=str(environment_file))
        if key is not None:
            env["MFI_IDENTITY_PK8_BASE64"] = base64.b64encode(key).decode()
        if certificate is not None:
            env["MFI_CERTIFICATE_P7B_BASE64"] = base64.b64encode(certificate).decode()
        env.update(secrets or {})
        result = subprocess.run(["python3", str(SCRIPT)], env=env, input=b"", capture_output=True, timeout=15)
        return result, runner / "iap2loc-mfi-assets", environment_file

    def assert_rejected(self, result, assets, environment_file):
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(environment_file.exists(), "failure must not export an assets directory")
        self.assertFalse(assets.exists(), "invalid material must not be published")

    def test_pkcs8_and_sec1_outputs_are_jvm_compatible_and_keep_the_same_key(self):
        identity = self.identities["accessory"]
        for encoding in ("pkcs8", "sec1"):
            with self.subTest(encoding=encoding):
                result, assets, environment_file = self.prepare(identity[encoding], identity["certificate"])
                self.assertEqual(0, result.returncode, result.stderr.decode())
                self.assertEqual(f"IAP2LOC_MFI_ASSETS={assets}\n", environment_file.read_text())
                output = assets / "offline-mfi"
                self.assertEqual({"identity.pk8", "certificate.p7b"}, {p.name for p in output.iterdir()})
                self.assertEqual(identity["certificate"], (output / "certificate.p7b").read_bytes())
                self.assertNotIn(base64.b64encode(identity[encoding]), result.stdout + result.stderr)
                public_file = self.work / "original-public.der"
                public_file.write_bytes(identity["public"])
                run("java", "-cp", str(self.root), "VerifyPkcs8", str(output / "identity.pk8"), str(public_file))

    def test_no_secrets_builds_without_assets(self):
        result, assets, environment_file = self.prepare()
        self.assertEqual(0, result.returncode)
        self.assertFalse(assets.exists())
        self.assertFalse(environment_file.exists())

    def test_each_missing_secret_is_rejected(self):
        identity = self.identities["accessory"]
        for key, certificate in ((identity["pkcs8"], None), (None, identity["certificate"])):
            with self.subTest(missing="certificate" if certificate is None else "key"):
                self.assert_rejected(*self.prepare(key, certificate))

    def test_wrong_curve_is_rejected(self):
        identity = self.identities["wrong-curve"]
        self.assert_rejected(*self.prepare(identity["pkcs8"], identity["certificate"]))

    def test_mismatched_certificate_is_rejected(self):
        self.assert_rejected(*self.prepare(self.identities["accessory"]["pkcs8"], self.identities["other"]["certificate"]))

    def test_malformed_base64_is_rejected(self):
        identity = self.identities["accessory"]
        for name in ("MFI_IDENTITY_PK8_BASE64", "MFI_CERTIFICATE_P7B_BASE64"):
            with self.subTest(secret=name):
                self.assert_rejected(*self.prepare(identity["pkcs8"], identity["certificate"], {name: "!invalid!"}))

    def test_corrupt_der_is_rejected(self):
        identity = self.identities["accessory"]
        for key, certificate in ((b"not a private key", identity["certificate"]), (identity["pkcs8"], b"not a certificate")):
            with self.subTest(corrupt="key" if key.startswith(b"not") else "certificate"):
                self.assert_rejected(*self.prepare(key, certificate))

    def test_pem_is_not_accepted_as_der(self):
        identity = self.identities["accessory"]
        self.assert_rejected(*self.prepare(identity["pem"], identity["certificate"]))

    def test_encrypted_key_fails_without_prompting(self):
        encrypted = run("openssl", "pkcs8", "-topk8", "-inform", "DER", "-outform", "DER",
                        "-passout", "pass:throwaway-test-password", data=self.identities["accessory"]["pkcs8"])
        self.assert_rejected(*self.prepare(encrypted, self.identities["accessory"]["certificate"]))


if __name__ == "__main__":
    unittest.main()
