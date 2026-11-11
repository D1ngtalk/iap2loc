using System.Net.Sockets;

namespace Iap2Loc.Transport;

/// <summary>
/// iAP2 over a raw TCP stream. This is the shape the wireless CarPlay link has once the AirPlay
/// session is up: the accessory gets a byte pipe that carries iAP2 CSM messages. Point it at
/// WinPlay's iapTunnel relay (or any other tunnel) to exercise the Wi-Fi path.
/// </summary>
internal sealed class TcpTransport : ITransport
{
    private readonly TcpClient _client;
    private readonly NetworkStream _stream;

    private TcpTransport(TcpClient client, string describe)
    {
        _client = client;
        _stream = client.GetStream();
        Describe = describe;
    }

    public string Describe { get; }

    public static TcpTransport Connect(string host, int port, int timeoutSeconds = 10)
    {
        var client = new TcpClient();
        try
        {
            if (!client.ConnectAsync(host, port).Wait(TimeSpan.FromSeconds(timeoutSeconds)))
            {
                throw new TimeoutException($"TCP connect to {host}:{port} timed out");
            }
        }
        catch (AggregateException ex)
        {
            client.Dispose();
            throw new IOException($"TCP connect to {host}:{port} failed: {ex.InnerException?.Message ?? ex.Message}");
        }
        catch
        {
            client.Dispose();
            throw;
        }

        client.NoDelay = true;
        return new TcpTransport(client, $"TCP {host}:{port}");
    }

    /// <summary>Wraps an already accepted socket, used by the loopback self test.</summary>
    public static TcpTransport FromClient(TcpClient client, string describe)
    {
        client.NoDelay = true;
        return new TcpTransport(client, describe);
    }

    public int Read(byte[] buffer, int offset, int count) => _stream.Read(buffer, offset, count);

    public void Write(byte[] buffer, int offset, int count) => _stream.Write(buffer, offset, count);

    public void Dispose()
    {
        try
        {
            _stream.Dispose();
        }
        finally
        {
            _client.Dispose();
        }
    }
}
