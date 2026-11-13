using System.Globalization;
using System.Security.Cryptography;
using System.Text.Json;

namespace Iap2Loc.Protocol;

/// <summary>
/// Everything iOS sees as "which accessory is this": the identification strings plus, optionally,
/// the Bluetooth address reported in transport component 17.
///
/// Rotating it is the cheapest way to find out whether iOS keeps per-accessory state (last known
/// location, trust) that survives reconnects. The profile is a file so the identity stays stable
/// between runs; a new one is generated on demand instead of on every start.
/// </summary>
internal sealed record AccessoryProfile
{
    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true,
    };

    public string Name { get; init; } = "iAP2 Loc";
    public string Model { get; init; } = "Loc1,1";
    public string Manufacturer { get; init; } = "carplay-loc";
    public string Serial { get; init; } = "LOC000000001";
    public string Firmware { get; init; } = "0.1.0";
    public string Hardware { get; init; } = "1.0";

    /// <summary>
    /// Overrides the adapter address in component 17. Null keeps the real one. Experimental: iOS may
    /// cross-check the reported address against the link, in which case identification gets rejected.
    /// </summary>
    public string? AdapterAddress { get; init; }

    public static AccessoryProfile Default { get; } = new();

    public static string DefaultPath
    {
        get
        {
            var root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            if (string.IsNullOrWhiteSpace(root))
            {
                root = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local", "share");
            }

            return Path.Combine(root, "iap2loc", "accessory.json");
        }
    }

    public AccessoryIdentity ToIdentity() => new(Name, Model, Manufacturer, Serial, Firmware, Hardware);

    /// <summary>Six bytes from "AA:BB:CC:DD:EE:FF", or null when unset or malformed.</summary>
    public byte[]? AdapterOverride()
    {
        if (string.IsNullOrWhiteSpace(AdapterAddress))
        {
            return null;
        }

        var parts = AdapterAddress.Split(new[] { ':', '-' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (parts.Length != 6)
        {
            return null;
        }

        var bytes = new byte[6];
        for (var i = 0; i < 6; i++)
        {
            if (!byte.TryParse(parts[i], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out bytes[i]))
            {
                return null;
            }
        }

        return bytes;
    }

    public AccessoryProfile Rotate()
    {
        var serial = "LC" + Convert.ToHexString(RandomNumberGenerator.GetBytes(6));
        return this with
        {
            Serial = serial,
            Name = "iAP2 Loc " + serial[^4..],
            AdapterAddress = null,
        };
    }

    /// <summary>A different unit from the same vendor: keeps the adapter's OUI, randomises the rest.</summary>
    public static string RandomAdapterAddress(byte[]? localAdapter)
    {
        var bytes = new byte[6];
        RandomNumberGenerator.Fill(bytes);
        if (localAdapter is { Length: 6 })
        {
            Array.Copy(localAdapter, bytes, 3);
        }

        return string.Join(':', bytes.Select(value => value.ToString("X2")));
    }

    public static AccessoryProfile Load(string path)
    {
        try
        {
            if (!File.Exists(path))
            {
                return Default;
            }

            var profile = JsonSerializer.Deserialize<AccessoryProfile>(File.ReadAllText(path), Json);
            return profile?.Sanitize() ?? Default;
        }
        catch (Exception ex)
        {
            Log.Warn($"配件身份文件无法读取，改用默认身份：{ex.Message}");
            return Default;
        }
    }

    public void Save(string path)
    {
        var full = Path.GetFullPath(path);
        var directory = Path.GetDirectoryName(full);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        File.WriteAllText(full, JsonSerializer.Serialize(this, Json));
    }

    private AccessoryProfile Sanitize() => this with
    {
        Name = Clean(Name, Default.Name),
        Model = Clean(Model, Default.Model),
        Manufacturer = Clean(Manufacturer, Default.Manufacturer),
        Serial = Clean(Serial, Default.Serial),
        Firmware = Clean(Firmware, Default.Firmware),
        Hardware = Clean(Hardware, Default.Hardware),
    };

    private static string Clean(string? value, string fallback) =>
        string.IsNullOrWhiteSpace(value) ? fallback : value.Trim();
}
