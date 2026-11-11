namespace Iap2Loc.Transport;

/// <summary>A byte duplex that iAP2 can run over: Bluetooth RFCOMM, a TCP tunnel, or an in-memory pair.</summary>
internal interface ITransport : IDisposable
{
    string Describe { get; }

    /// <summary>Blocking read. Returns the number of bytes read, or 0 when the peer closed.</summary>
    int Read(byte[] buffer, int offset, int count);

    void Write(byte[] buffer, int offset, int count);
}
