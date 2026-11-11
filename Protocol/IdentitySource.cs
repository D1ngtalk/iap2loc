namespace Iap2Loc.Protocol;

/// <summary>
/// Optional identity source for custom builds. The stock build installs no provider and simply
/// reads identity.pk8 + certificate.p7b from a directory.
/// </summary>
internal interface IIdentityProvider
{
    string Name { get; }

    /// <summary>Human readable problem to surface in the console, if any.</summary>
    string? Warning { get; }

    MfiSigner? Resolve(string? directory, out string description);

    /// <summary>Called once when the web console starts, before any session exists.</summary>
    void Prime();
}

/// <summary>Resolves the signing material for a session.</summary>
internal static class IdentitySource
{
    public static IIdentityProvider? Provider { get; set; }

    public static string? Warning => Provider?.Warning;

    public static MfiSigner? Resolve(string? directory, out string description)
    {
        if (Provider is not null)
        {
            return Provider.Resolve(directory, out description);
        }

        if (!string.IsNullOrWhiteSpace(directory))
        {
            description = $"文件 {Path.GetFullPath(directory)}";
            return MfiSigner.Load(directory);
        }

        description = "无";
        return null;
    }

    public static void Prime() => Provider?.Prime();
}
