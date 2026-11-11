using System.Runtime.InteropServices;

namespace Iap2Loc.Transport;

internal sealed record BluetoothDevice(
    string Address,
    string Name,
    bool Connected,
    bool Remembered,
    bool Paired,
    bool LikelyApple);

/// <summary>Lists Bluetooth radios and the devices Windows already knows about (paired or remembered).</summary>
internal static class BluetoothDevices
{
    // A handful of very common Apple prefixes. The name and device-class checks catch the rest,
    // including phones the user has renamed.
    private static readonly HashSet<string> ApplePrefixes = new(StringComparer.OrdinalIgnoreCase)
    {
        "00:1B:63", "00:1E:C2", "00:1F:F3", "00:23:12", "00:25:00", "00:26:08", "00:26:BB",
        "04:0C:CE", "04:15:52", "04:1E:64", "04:26:65", "04:48:9A", "04:52:F3", "04:54:53",
        "08:66:98", "08:6D:41", "08:70:45", "08:74:02", "0C:30:21", "0C:3E:9F", "0C:74:C2",
        "10:40:F3", "10:41:7F", "10:93:E9", "10:9A:DD", "14:10:9F", "14:20:5E", "14:5A:05",
        "18:20:32", "18:34:51", "18:65:90", "18:AF:61", "1C:1A:C0", "1C:36:BB", "1C:5C:F2",
        "20:78:F0", "20:7D:74", "20:9B:CD", "24:1E:EB", "24:A0:74", "24:A2:E1", "24:AB:81",
        "28:0B:5C", "28:37:37", "28:5A:EB", "28:6A:BA", "28:CF:DA", "2C:1F:23", "2C:20:0B",
        "2C:33:61", "2C:54:CF", "30:10:E4", "30:35:AD", "30:63:6B", "30:90:AB", "34:08:BC",
        "34:12:98", "34:15:9E", "34:36:3B", "34:51:C9", "38:0F:4A", "38:48:4C", "38:71:DE",
        "38:89:2C", "38:B5:4D", "38:C9:86", "3C:07:54", "3C:15:C2", "3C:22:FB", "3C:AB:8E",
        "40:30:04", "40:33:1A", "40:6C:8F", "40:98:AD", "40:A6:D9", "40:B3:95", "44:00:10",
        "44:2A:60", "44:4C:0C", "44:58:29", "44:61:32", "44:D8:84", "48:43:7C", "48:60:BC",
        "48:74:6E", "48:A1:95", "48:B7:2F", "4C:57:CA", "4C:74:03", "4C:8D:79", "50:32:37",
        "50:82:D5", "54:26:96", "54:4E:90", "54:72:4F", "54:9F:13", "54:AE:27", "58:1F:AA",
        "58:40:4E", "58:55:CA", "58:B0:35", "5C:59:48", "5C:95:AE", "5C:96:9D", "5C:97:F3",
        "60:03:08", "60:33:4B", "60:69:44", "60:8C:4A", "60:92:17", "60:C5:47", "60:D9:C7",
        "64:20:0C", "64:76:BA", "64:9A:BE", "64:B0:A6", "64:B9:E8", "68:09:27", "68:5B:35",
        "68:64:4B", "68:96:7B", "68:9C:70", "68:A8:6D", "68:AB:1E", "68:AE:20", "68:D9:3C",
        "6C:19:C0", "6C:3E:6D", "6C:40:08", "6C:70:9F", "6C:72:E7", "6C:8D:C1", "6C:94:F8",
        "70:11:24", "70:14:A6", "70:3E:AC", "70:48:0F", "70:56:81", "70:73:CB", "70:DE:E2",
        "74:1B:B2", "74:8D:08", "74:E1:B6", "74:E2:F5", "78:31:C1", "78:3A:84", "78:4F:43",
        "78:6C:1C", "78:7B:8A", "78:88:6D", "78:CA:39", "7C:01:91", "7C:11:BE", "7C:6D:62",
        "7C:C3:A1", "7C:C5:37", "7C:D1:C3", "7C:F0:5F", "80:00:6E", "80:49:71", "80:92:9F",
        "80:B0:3D", "80:BE:05", "80:D6:05", "80:E6:50", "80:EA:96", "84:38:35", "84:41:67",
        "84:78:8B", "84:85:06", "84:89:AD", "84:8E:0C", "84:B1:53", "84:F3:EB", "88:19:08",
        "88:1D:FC", "88:53:95", "88:63:DF", "88:66:A5", "88:6B:6E", "88:C6:63", "88:CB:87",
        "8C:00:6D", "8C:29:37", "8C:2D:AA", "8C:58:77", "8C:7B:9D", "8C:7C:92", "8C:85:90",
        "90:0F:C6", "90:27:E4", "90:3C:92", "90:60:F1", "90:72:40", "90:81:2A", "90:84:0D",
        "94:16:25", "94:56:41", "94:94:26", "94:B0:1F", "94:E9:6A", "98:01:A7", "98:03:D8",
        "98:5A:EB", "98:6A:0B", "98:B8:E3", "98:D6:BB", "98:E0:D9", "9C:04:EB", "9C:20:7B",
        "9C:29:3F", "9C:35:EB", "9C:4F:DA", "9C:64:8B", "9C:84:BF", "9C:8B:A0", "9C:E6:5E",
        "A0:18:28", "A0:20:A6", "A0:3B:E3", "A0:4E:A7", "A0:99:9B", "A0:D7:95", "A0:ED:CD",
        "A4:13:4E", "A4:26:18", "A4:31:35", "A4:5E:60", "A4:67:06", "A4:83:E7", "A4:B1:97",
        "A4:C3:61", "A4:D1:8C", "A8:20:66", "A8:5B:78", "A8:86:DD", "A8:88:08", "A8:8E:24",
        "A8:96:8A", "A8:BB:CF", "A8:C8:85", "A8:D0:E5", "A8:E2:C1", "AC:29:3A", "AC:34:CB",
        "AC:61:EA", "AC:7F:3E", "AC:85:3D", "AC:87:A3", "AC:BC:32", "AC:CF:5C", "AC:E4:B5",
        "B0:34:95", "B0:65:BD", "B0:70:2D", "B0:75:0D", "B0:9F:BA", "B0:C1:9E", "B0:C4:E7",
        "B4:18:D1", "B4:4B:D6", "B4:8B:19", "B4:9C:DF", "B4:F0:AB", "B4:F6:1C", "B8:09:8A",
        "B8:17:C2", "B8:41:A4", "B8:44:D9", "B8:53:AC", "B8:5D:0A", "B8:63:4D", "B8:78:2E",
        "B8:8D:12", "B8:90:47", "B8:98:B0", "B8:B2:F8", "B8:C1:11", "B8:C7:5D", "B8:E8:56",
        "BC:3B:AF", "BC:4C:C4", "BC:52:B7", "BC:54:36", "BC:67:78", "BC:6C:21", "BC:72:B1",
        "C0:1A:DA", "C0:63:94", "C0:84:7A", "C0:9F:42", "C0:CE:CD", "C0:D0:12", "C0:F2:FB",
        "C4:2C:03", "C4:B3:01", "C4:BC:BD", "C4:D9:87", "C4:E9:84", "C8:1E:E7", "C8:2A:14",
        "C8:3C:85", "C8:69:CD", "C8:6F:1D", "C8:85:50", "C8:B5:B7", "C8:BC:C8", "C8:D0:83",
        "CC:08:8D", "CC:20:E8", "CC:25:EF", "CC:29:F5", "CC:44:63", "CC:78:5F", "CC:C7:60",
        "D0:03:4B", "D0:23:DB", "D0:25:98", "D0:33:11", "D0:4F:7E", "D0:81:7A", "D0:A6:37",
        "D0:C5:F3", "D0:D2:B0", "D0:E1:40", "D4:61:9D", "D4:9A:20", "D4:DC:CD", "D4:F4:6F",
        "D8:00:4D", "D8:1D:72", "D8:30:62", "D8:96:95", "D8:9E:3F", "D8:A2:5E", "D8:BB:2C",
        "DC:0C:5C", "DC:2B:2A", "DC:2B:61", "DC:37:14", "DC:41:5F", "DC:52:85", "DC:86:D8",
        "E0:5F:45", "E0:66:78", "E0:AC:CB", "E0:B5:2D", "E0:B9:BA", "E0:C7:67", "E0:C9:7A",
        "E4:25:E7", "E4:50:EB", "E4:8B:7F", "E4:9A:79", "E4:C6:3D", "E4:CE:8F", "E4:E4:AB",
        "E8:04:0B", "E8:06:88", "E8:80:2E", "E8:8D:28", "E8:B2:AC", "E8:EA:6A", "EC:35:86",
        "EC:85:2F", "EC:AD:B8", "F0:18:98", "F0:24:75", "F0:99:BF", "F0:B0:E7", "F0:C1:F1",
        "F0:D1:A9", "F0:DB:E2", "F0:DC:E2", "F4:0F:24", "F4:15:63", "F4:31:C3", "F4:37:B7",
        "F4:5C:89", "F4:F1:5A", "F4:F9:51", "F8:1E:DF", "F8:27:93", "F8:38:80", "F8:4D:89",
        "F8:56:C3", "F8:95:C7", "F8:B1:56", "F8:C3:9E", "F8:FF:C2", "FC:25:3F", "FC:2A:9C",
        "FC:D8:48", "FC:E9:98", "FC:FC:48",
    };

    public static List<BluetoothDevice> GetDevices()
    {
        var result = new List<BluetoothDevice>();
        var radioParams = new BluetoothFindRadioParams { Size = (uint)Marshal.SizeOf<BluetoothFindRadioParams>() };
        var find = Native.BluetoothFindFirstRadio(ref radioParams, out var radio);
        if (find == IntPtr.Zero)
        {
            throw new InvalidOperationException("Windows 未检测到可用的蓝牙适配器");
        }

        try
        {
            var search = new BluetoothDeviceSearchParams
            {
                Size = (uint)Marshal.SizeOf<BluetoothDeviceSearchParams>(),
                ReturnAuthenticated = 1,
                ReturnRemembered = 1,
                ReturnConnected = 1,
                ReturnUnknown = 0,
                IssueInquiry = 0,
                TimeoutMultiplier = 0,
                Radio = radio,
            };

            var device = new BluetoothDeviceInfo { Size = (uint)Marshal.SizeOf<BluetoothDeviceInfo>() };
            var found = Native.BluetoothFindFirstDevice(ref search, ref device);
            if (found == IntPtr.Zero)
            {
                return result;
            }

            try
            {
                do
                {
                    var address = FormatAddress(device.Address);
                    result.Add(new BluetoothDevice(
                        address,
                        string.IsNullOrWhiteSpace(device.Name) ? "(未命名)" : device.Name,
                        device.Connected != 0,
                        device.Remembered != 0,
                        device.Authenticated != 0,
                        LooksLikeApple(device.Name ?? string.Empty, device.ClassOfDevice, address)));
                    device.Size = (uint)Marshal.SizeOf<BluetoothDeviceInfo>();
                }
                while (Native.BluetoothFindNextDevice(found, ref device));
            }
            finally
            {
                Native.BluetoothFindDeviceClose(found);
            }
        }
        finally
        {
            Native.BluetoothFindRadioClose(find);
            Native.CloseHandle(radio);
        }

        return result
            .OrderByDescending(device => device.LikelyApple)
            .ThenByDescending(device => device.Connected)
            .ThenBy(device => device.Name, StringComparer.CurrentCulture)
            .ToList();
    }

    /// <summary>Phone device class, an Apple name, or a known Apple OUI — any one is enough.</summary>
    private static bool LooksLikeApple(string name, uint classOfDevice, string address)
    {
        if (name.Contains("iPhone", StringComparison.OrdinalIgnoreCase) ||
            name.Contains("iPad", StringComparison.OrdinalIgnoreCase) ||
            name.Contains("iPod", StringComparison.OrdinalIgnoreCase) ||
            name.Contains("Apple Watch", StringComparison.OrdinalIgnoreCase) ||
            name.Contains("AirPods", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        var majorClass = (int)((classOfDevice >> 8) & 0x1F);
        if (majorClass == 0x02)
        {
            return true;
        }

        return address.Length >= 8 && ApplePrefixes.Contains(address[..8]);
    }

    public static void List()
    {
        var devices = GetDevices();
        if (devices.Count == 0)
        {
            Log.Warn("没有已配对或已记住的蓝牙设备");
        }

        foreach (var device in devices)
        {
            var flags = new List<string>();
            if (device.Connected) flags.Add("connected");
            if (device.Remembered) flags.Add("remembered");
            if (device.Paired) flags.Add("paired");
            if (device.LikelyApple) flags.Add("apple?");
            Log.Info($"{device.Address}  {device.Name,-32}  {string.Join(", ", flags)}");
        }

        Log.Info("先在 Windows 蓝牙设置里配对 iPhone，再用 --mac 指定地址");
    }

    public static string FormatAddress(ulong address) =>
        string.Join(':', AddressBytes(address).Select(b => b.ToString("X2")));

    /// <summary>Six bytes in display order (AA:BB:CC:...), the order iAP2 expects in a transport component.</summary>
    public static byte[] AddressBytes(ulong address) =>
        BitConverter.GetBytes(address).Take(6).Reverse().ToArray();

    /// <summary>The local adapter address, needed for identification parameter 17 (Bluetooth transport).</summary>
    public static byte[]? TryGetLocalAdapterAddress()
    {
        var radioParams = new BluetoothFindRadioParams { Size = (uint)Marshal.SizeOf<BluetoothFindRadioParams>() };
        var find = Native.BluetoothFindFirstRadio(ref radioParams, out var radio);
        if (find == IntPtr.Zero)
        {
            return null;
        }

        try
        {
            var info = new BluetoothRadioInfo { Size = (uint)Marshal.SizeOf<BluetoothRadioInfo>() };
            return Native.BluetoothGetRadioInfo(radio, ref info) != 0 ? null : AddressBytes(info.Address);
        }
        finally
        {
            Native.BluetoothFindRadioClose(find);
            Native.CloseHandle(radio);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BluetoothFindRadioParams
    {
        public uint Size;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct BluetoothRadioInfo
    {
        public uint Size;
        public ulong Address;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)]
        public string Name;
        public uint ClassOfDevice;
        public ushort LmpSubversion;
        public ushort Manufacturer;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BluetoothDeviceSearchParams
    {
        public uint Size;
        public int ReturnAuthenticated;
        public int ReturnRemembered;
        public int ReturnUnknown;
        public int ReturnConnected;
        public int IssueInquiry;
        public byte TimeoutMultiplier;
        public IntPtr Radio;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct BluetoothDeviceInfo
    {
        public uint Size;
        public ulong Address;
        public uint ClassOfDevice;
        public int Connected;
        public int Remembered;
        public int Authenticated;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)]
        public byte[] LastSeen;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)]
        public byte[] LastUsed;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)]
        public string Name;
    }

    private static class Native
    {
        [DllImport("bthprops.cpl")]
        public static extern IntPtr BluetoothFindFirstRadio(ref BluetoothFindRadioParams parameters, out IntPtr radio);

        [DllImport("bthprops.cpl")]
        public static extern bool BluetoothFindRadioClose(IntPtr find);

        [DllImport("bthprops.cpl")]
        public static extern uint BluetoothGetRadioInfo(IntPtr radio, ref BluetoothRadioInfo info);

        [DllImport("bthprops.cpl")]
        public static extern IntPtr BluetoothFindFirstDevice(ref BluetoothDeviceSearchParams parameters, ref BluetoothDeviceInfo info);

        [DllImport("bthprops.cpl")]
        public static extern bool BluetoothFindNextDevice(IntPtr find, ref BluetoothDeviceInfo info);

        [DllImport("bthprops.cpl")]
        public static extern bool BluetoothFindDeviceClose(IntPtr find);

        [DllImport("kernel32.dll")]
        public static extern bool CloseHandle(IntPtr handle);
    }
}
