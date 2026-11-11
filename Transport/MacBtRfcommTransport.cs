using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Microsoft.Win32.SafeHandles;

namespace Iap2Loc.Transport;

/// <summary>
/// macOS Classic Bluetooth RFCOMM transport. Native IOBluetooth runs a runloop
/// and bridges bytes through a local UNIX socketpair. This keeps iAP2 unchanged.
/// </summary>
internal sealed class MacBtRfcommTransport : ITransport
{
    private readonly Socket _socket;
    private readonly object _writeLock = new();
    private bool _disposed;

    private MacBtRfcommTransport(Socket socket, string address)
    {
        _socket = socket;
        Describe = $"macOS IOBluetooth RFCOMM {address}";
    }

    public string Describe { get; }

    public static ITransport Connect(string address, int timeoutSeconds = 20)
    {
        if (!OperatingSystem.IsMacOS())
            throw new PlatformNotSupportedException("macOS IOBluetooth is only available on macOS.");

        var normalized = BtRfcommTransport.Normalize(address);
        var error = new byte[1024];
        int fd = Native.Open(normalized, Math.Clamp(timeoutSeconds, 1, 120) * 1000, error, error.Length);
        if (fd < 0)
        {
            var message = Encoding.UTF8.GetString(error).TrimEnd('\0');
            throw new IOException(string.IsNullOrEmpty(message)
                ? "IOBluetooth RFCOMM connection failed (check Bluetooth permissions)."
                : message);
        }

        try
        {
            // The native side uses IOBluetooth, but the managed side needs only a byte stream.
            return new MacBtRfcommTransport(
                new Socket(new SafeSocketHandle(new IntPtr(fd), ownsHandle: true)), normalized);
        }
        catch
        {
            Native.CloseFd(fd);
            throw;
        }
    }

    public static List<BluetoothDevice> GetDevices()
    {
        var bytes = new byte[65536];
        int length = Native.PairedDevices(bytes, bytes.Length);
        if (length < 0)
            throw new IOException("IOBluetooth paired-device enumeration failed.");

        var records = JsonSerializer.Deserialize<List<MacPairedDevice>>(
            bytes.AsSpan(0, length),
            new JsonSerializerOptions { PropertyNameCaseInsensitive = true }) ?? new();

        return records
            .Where(d => !string.IsNullOrWhiteSpace(d.Address))
            .Select(d => {
                var normalized = d.Address!.Replace('-', ':').ToUpperInvariant();
                return new BluetoothDevice(
                    normalized, d.Name ?? "(未命名)", d.Connected, true, d.Paired,
                    BluetoothDevices.IsLikelyApple(d.Name ?? "", normalized));
            })
            .OrderByDescending(d => d.LikelyApple)
            .ThenByDescending(d => d.Connected)
            .ThenBy(d => d.Name, StringComparer.CurrentCulture)
            .ToList();
    }

    public static byte[]? GetLocalAdapterAddress()
    {
        var bytes = new byte[64];
        int length = Native.LocalAddress(bytes, bytes.Length);
        if (length <= 0) return null;
        var text = Encoding.UTF8.GetString(bytes, 0, length);
        var hex = new string(text.Where(Uri.IsHexDigit).ToArray());
        if (hex.Length != 12) return null;
        return Convert.FromHexString(hex);
    }

    public int Read(byte[] buffer, int offset, int count)
    {
        if (_disposed) return 0;
        try
        {
            return _socket.Receive(buffer, offset, count, SocketFlags.None);
        }
        catch (ObjectDisposedException)
        {
            return 0;
        }
        catch (SocketException) when (_disposed)
        {
            return 0;
        }
    }

    public void Write(byte[] buffer, int offset, int count)
    {
        lock (_writeLock)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            while (count > 0)
            {
                int sent = _socket.Send(buffer, offset, count, SocketFlags.None);
                if (sent == 0) throw new IOException("IOBluetooth bridge socket closed while writing.");
                offset += sent;
                count -= sent;
            }
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try { _socket.Shutdown(SocketShutdown.Both); }
        catch (SocketException) { }
        catch (ObjectDisposedException) { }
        _socket.Dispose();
    }

    private sealed record MacPairedDevice(string? Address, string? Name, bool Paired, bool Connected);

    private static class Native
    {
        [DllImport("iap2macbt", EntryPoint = "iap2mac_open",
            CharSet = CharSet.Ansi, CallingConvention = CallingConvention.Cdecl)]
        public static extern int Open(string address, int timeoutMs,
            [Out] byte[] error, int capacity);

        [DllImport("iap2macbt", EntryPoint = "iap2mac_paired_devices",
            CallingConvention = CallingConvention.Cdecl)]
        public static extern int PairedDevices([Out] byte[] buffer, int capacity);

        [DllImport("iap2macbt", EntryPoint = "iap2mac_local_address",
            CallingConvention = CallingConvention.Cdecl)]
        public static extern int LocalAddress([Out] byte[] buffer, int capacity);

        [DllImport("libSystem.B.dylib", EntryPoint = "close", CallingConvention = CallingConvention.Cdecl)]
        public static extern int CloseFd(int fd);
    }
}
