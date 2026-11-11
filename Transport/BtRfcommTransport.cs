using System.Runtime.InteropServices;

namespace Iap2Loc.Transport;

/// <summary>
/// Classic Bluetooth RFCOMM client for the iPhone iAP2 device service
/// (00000000-deca-fade-deca-deafdecacafe), implemented directly on Winsock AF_BTH.
///
/// The accessory is the RFCOMM client: Windows resolves the UUID through SDP, so no local
/// SDP record has to be registered. The device must already be paired in Windows Settings.
/// </summary>
internal sealed class BtRfcommTransport : ITransport
{
    private const int AfBth = 32;
    private const int SockStream = 1;
    private const int BthProtoRfcomm = 3;
    private static readonly Guid Iap2DeviceService = new("00000000-deca-fade-deca-deafdecacafe");

    private readonly IntPtr _socket;
    private readonly object _writeGate = new();
    private bool _closed;

    private BtRfcommTransport(IntPtr socket, string mac)
    {
        _socket = socket;
        Describe = $"RFCOMM {mac}";
    }

    public string Describe { get; }

    public static BtRfcommTransport Connect(string mac, int timeoutSeconds = 20)
    {
        var normalized = Normalize(mac);
        if (Ws2_32.WSAStartup(0x0202, new byte[512]) != 0)
        {
            throw new InvalidOperationException("WSAStartup failed");
        }

        var socket = Ws2_32.socket(AfBth, SockStream, BthProtoRfcomm);
        if (socket == new IntPtr(-1))
        {
            throw new InvalidOperationException($"socket(AF_BTH) failed, WSA error {Ws2_32.WSAGetLastError()}");
        }

        var address = new SockAddrBth
        {
            AddressFamily = AfBth,
            BluetoothAddress = ulong.Parse(normalized.Replace(":", string.Empty), System.Globalization.NumberStyles.HexNumber),
            ServiceClassId = Iap2DeviceService,
            Port = 0,
        };

        if (Ws2_32.connect(socket, ref address, Marshal.SizeOf<SockAddrBth>()) != 0)
        {
            var error = Ws2_32.WSAGetLastError();
            Ws2_32.closesocket(socket);
            throw new InvalidOperationException(error switch
            {
                10060 => "RFCOMM/SDP connect timed out (10060). The phone did not answer the CarPlay/iAP2 " +
                         "Bluetooth service: check that the device is the iPhone, is paired in Windows " +
                         "Bluetooth settings, and is awake.",
                10049 => "RFCOMM connect failed (10049): the Bluetooth address cannot be reached.",
                10051 => "RFCOMM connect failed (10051): no route to the device, check the adapter.",
                _ => $"RFCOMM connect failed, WSA error {error}",
            });
        }

        return new BtRfcommTransport(socket, normalized);
    }

    public static string Normalize(string mac)
    {
        var hex = new string(mac.Where(Uri.IsHexDigit).ToArray()).ToUpperInvariant();
        if (hex.Length != 12)
        {
            throw new ArgumentException($"Bluetooth address must have 12 hex digits, got '{mac}'");
        }

        return string.Join(':', Enumerable.Range(0, 6).Select(i => hex.Substring(i * 2, 2)));
    }

    public int Read(byte[] buffer, int offset, int count)
    {
        if (offset != 0)
        {
            throw new NotSupportedException("offset must be 0");
        }

        while (true)
        {
            var read = Ws2_32.recv(_socket, buffer, count, 0);
            if (read > 0)
            {
                return read;
            }

            if (read == 0)
            {
                return 0;
            }

            var error = Ws2_32.WSAGetLastError();
            if (error == 10035)
            {
                Thread.Sleep(5);
                continue;
            }

            if (_closed)
            {
                return 0;
            }

            throw new IOException($"RFCOMM receive failed, WSA error {error}");
        }
    }

    public void Write(byte[] buffer, int offset, int count)
    {
        lock (_writeGate)
        {
            var sent = 0;
            while (sent < count)
            {
                var chunk = new byte[count - sent];
                Buffer.BlockCopy(buffer, offset + sent, chunk, 0, chunk.Length);
                var written = Ws2_32.send(_socket, chunk, chunk.Length, 0);
                if (written <= 0)
                {
                    throw new IOException($"RFCOMM send failed, WSA error {Ws2_32.WSAGetLastError()}");
                }

                sent += written;
            }
        }
    }

    public void Dispose()
    {
        if (_closed)
        {
            return;
        }

        _closed = true;
        Ws2_32.closesocket(_socket);
        Ws2_32.WSACleanup();
    }

    /// <summary>ws2bth.h declares SOCKADDR_BTH with 1-byte packing; the address must sit at offset 2.</summary>
    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    private struct SockAddrBth
    {
        public ushort AddressFamily;
        public ulong BluetoothAddress;
        public Guid ServiceClassId;
        public uint Port;
    }

    private static class Ws2_32
    {
        [DllImport("ws2_32.dll")]
        public static extern int WSAStartup(ushort version, byte[] data);

        [DllImport("ws2_32.dll")]
        public static extern int WSACleanup();

        [DllImport("ws2_32.dll")]
        public static extern IntPtr socket(int family, int type, int protocol);

        [DllImport("ws2_32.dll")]
        public static extern int connect(IntPtr socket, ref SockAddrBth address, int length);

        [DllImport("ws2_32.dll")]
        public static extern int recv(IntPtr socket, byte[] buffer, int count, int flags);

        [DllImport("ws2_32.dll")]
        public static extern int send(IntPtr socket, byte[] buffer, int count, int flags);

        [DllImport("ws2_32.dll")]
        public static extern int closesocket(IntPtr socket);

        [DllImport("ws2_32.dll")]
        public static extern int WSAGetLastError();
    }
}
