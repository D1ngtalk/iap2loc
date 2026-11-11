using System.Globalization;
using System.Numerics;
using System.Security.Cryptography;

namespace Iap2Loc.Protocol;

/// <summary>
/// MFi authentication 3.0: the accessory holds a P-256 key (identity.pk8) and its certificate
/// chain (certificate.p7b). The phone sends a 32-byte challenge; the accessory answers with a
/// 64-byte r||s ECDSA signature over that challenge.
///
/// This project does not ship any authentication material. Point --identity at a directory that
/// contains your own identity.pk8 and certificate.p7b.
/// </summary>
internal sealed class MfiSigner : IDisposable
{
    private static readonly BigInteger P256Order = BigInteger.Parse(
        "0FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
        NumberStyles.HexNumber,
        CultureInfo.InvariantCulture);

    private readonly ECDsa _key;

    private MfiSigner(ECDsa key, byte[] certificate)
    {
        _key = key;
        Certificate = certificate;
    }

    public byte[] Certificate { get; }

    public static MfiSigner Load(string directory)
    {
        var keyPath = Path.Combine(directory, "identity.pk8");
        var certificatePath = Path.Combine(directory, "certificate.p7b");
        if (!File.Exists(keyPath) || !File.Exists(certificatePath))
        {
            throw new FileNotFoundException(
                $"MFi material missing: expected {keyPath} and {certificatePath}");
        }

        return FromMaterial(File.ReadAllBytes(keyPath), File.ReadAllBytes(certificatePath));
    }

    /// <summary>Builds a signer from in-memory material, used by the embedded identity path.</summary>
    public static MfiSigner FromMaterial(byte[] pk8, byte[] certificate)
    {
        var key = ECDsa.Create();
        try
        {
            key.ImportPkcs8PrivateKey(pk8, out _);
        }
        catch (Exception ex)
        {
            key.Dispose();
            throw new InvalidOperationException($"cannot import identity.pk8: {ex.Message}", ex);
        }

        if (key.KeySize != 256)
        {
            key.Dispose();
            throw new InvalidOperationException($"MFi 3.0 expects a P-256 key, got {key.KeySize} bits");
        }

        return new MfiSigner(key, certificate);
    }

    /// <summary>Signs the challenge as a digest. The result is low-S so it looks like a coprocessor signature.</summary>
    public byte[] SignChallenge(byte[] challenge)
    {
        if (challenge.Length != 32)
        {
            throw new InvalidOperationException(
                $"MFi 3.0 challenge must be 32 bytes, got {challenge.Length}; the phone may be using " +
                "MFi 2.0C (RSA/SHA-1), which this implementation does not support");
        }

        var signature = _key.SignHash(challenge, DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        return NormalizeLowS(signature);
    }

    private static byte[] NormalizeLowS(byte[] signature)
    {
        if (signature.Length != 64)
        {
            return signature;
        }

        var s = new BigInteger(signature.AsSpan(32, 32), isUnsigned: true, isBigEndian: true);
        if (s <= P256Order / 2)
        {
            return signature;
        }

        var normalized = (P256Order - s).ToByteArray(isUnsigned: true, isBigEndian: true);
        var result = (byte[])signature.Clone();
        Array.Clear(result, 32, 32);
        Buffer.BlockCopy(normalized, 0, result, 64 - normalized.Length, normalized.Length);
        return result;
    }

    public void Dispose() => _key.Dispose();
}
