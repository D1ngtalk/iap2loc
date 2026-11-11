using Iap2Loc.Transport;

namespace Iap2Loc.Protocol;

internal enum LinkState
{
    Detect,
    Negotiate,
    Normal,
    Dead,
}

/// <summary>
/// iAP2 link layer: detect marker, rate negotiation, sequence/acknowledge, retransmission and
/// control-session (CSM) framing. Behaviour mirrors the working accessory implementations in
/// WinPlay and DiPlay, rewritten from scratch.
/// </summary>
internal sealed class Iap2Link : IDisposable
{
    public const byte SessionControl = 10;

    private const byte ControlAck = 0x40;
    private const byte ControlRetransmit = 0x20;
    private const byte ControlSync = 0x80;
    private const byte ControlReset = 0x10;

    private static readonly byte[] Marker = { 0xFF, 0x55, 0x02, 0x00, 0xEE, 0x10 };

    // version, maxOutgoing, maxLength (65535), timeout (4000 ms), retries, then session TLVs.
    private static readonly byte[] SyncPayload =
    {
        1, 4, 255, 255, 15, 160, 1, 244, 4, 3, 10, 0, 2, 11, 2, 1, 12, 1, 2,
    };

    private readonly ITransport _transport;
    private readonly bool _initiate;
    private readonly Thread _reader;
    private readonly Timer _timer;
    private readonly object _gate = new();

    private readonly List<Pending> _pending = new();
    private readonly Queue<Queued> _queue = new();
    private readonly Dictionary<int, (byte Session, byte[] Data)> _outOfOrder = new();

    private byte[] _readBuffer = new byte[64 * 1024];
    private int _readLength;
    private byte[] _csmBuffer = new byte[64 * 1024];
    private int _csmLength;

    private LinkState _state = LinkState.Detect;
    private bool _started;
    private byte _sequence;
    private byte _acknowledge;
    private int _maxOutgoing = 4;
    private int _maxLength = 4000;
    private int _timeoutMs = 500;
    private int _maxRetries = 4;
    private long _startedAt;
    private long _lastMarkerAt;
    private long _lastSyncAt;
    private bool _disposed;

    public Iap2Link(ITransport transport, bool initiate, string role)
    {
        _transport = transport;
        _initiate = initiate;
        Role = role;
        _reader = new Thread(ReadLoop) { IsBackground = true, Name = $"iap2-rx-{role}" };
        _timer = new Timer(_ => Tick(), null, 100, 100);
    }

    public string Role { get; }

    public long BytesIn { get; private set; }

    public long BytesOut { get; private set; }

    public int CsmMessages { get; private set; }

    public event Action<ushort, IReadOnlyDictionary<int, byte[]>>? CsmReceived;

    public event Action? Ready;

    public event Action<string>? Failed;

    public LinkState State
    {
        get
        {
            lock (_gate)
            {
                return _state;
            }
        }
    }

    public void Start()
    {
        lock (_gate)
        {
            _started = true;
            _startedAt = Environment.TickCount64;
            if (_initiate)
            {
                WriteRaw(Marker);
                _lastMarkerAt = _startedAt;
            }
        }

        _reader.Start();
    }

    public void Send(ushort messageId, params byte[][] parameters) =>
        SendRaw(Csm.Encode(messageId, parameters), SessionControl);

    public void SendRaw(byte[] data, byte session)
    {
        lock (_gate)
        {
            if (_state == LinkState.Dead)
            {
                throw new InvalidOperationException("iAP2 link is closed");
            }

            var chunkSize = Math.Max(64, _maxLength - 10);
            for (var offset = 0; offset < data.Length; offset += chunkSize)
            {
                var size = Math.Min(chunkSize, data.Length - offset);
                var chunk = new byte[size];
                Buffer.BlockCopy(data, offset, chunk, 0, size);
                _queue.Enqueue(new Queued(session, chunk));
            }

            Flush();
        }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed)
            {
                return;
            }

            _disposed = true;
            _state = LinkState.Dead;
        }

        _timer.Dispose();
        _transport.Dispose();
    }

    private void ReadLoop()
    {
        var buffer = new byte[8192];
        while (true)
        {
            int read;
            try
            {
                read = _transport.Read(buffer, 0, buffer.Length);
            }
            catch (Exception ex)
            {
                Fail($"transport read failed: {ex.Message}");
                return;
            }

            if (read <= 0)
            {
                Fail("transport closed by the peer");
                return;
            }

            lock (_gate)
            {
                BytesIn += read;
                Append(buffer, read);
                Process();
            }
        }
    }

    private void Append(byte[] data, int count)
    {
        if (_readLength + count > _readBuffer.Length)
        {
            Array.Resize(ref _readBuffer, Math.Max(_readBuffer.Length * 2, _readLength + count));
        }

        Buffer.BlockCopy(data, 0, _readBuffer, _readLength, count);
        _readLength += count;
    }

    private void Process()
    {
        if (_state == LinkState.Detect)
        {
            if (_readLength < Marker.Length)
            {
                return;
            }

            if (!_readBuffer.AsSpan(0, Marker.Length).SequenceEqual(Marker))
            {
                Fail("the device did not answer the iAP2 detect marker");
                return;
            }

            ConsumeRead(Marker.Length);
            if (!_initiate)
            {
                // The peer opened the conversation; answer with our own marker before negotiating.
                WriteRaw(Marker);
            }

            _state = LinkState.Negotiate;
            _startedAt = Environment.TickCount64;
            Log.Trace($"[{Role}] marker exchanged, negotiating");
            SendSync();
        }

        while (_state != LinkState.Dead && _readLength >= 9)
        {
            if (_readLength >= Marker.Length && _readBuffer.AsSpan(0, Marker.Length).SequenceEqual(Marker))
            {
                ConsumeRead(Marker.Length);
                continue;
            }

            if (_readBuffer[0] != 0xFF || _readBuffer[1] != 0x5A)
            {
                ConsumeRead(1);
                continue;
            }

            var length = (_readBuffer[2] << 8) | _readBuffer[3];
            if (length < 9)
            {
                Fail($"invalid iAP2 packet length {length}");
                return;
            }

            if (_readLength < length)
            {
                return;
            }

            var packet = new byte[length];
            Buffer.BlockCopy(_readBuffer, 0, packet, 0, length);
            ConsumeRead(length);
            Handle(packet);
        }
    }

    private void Handle(byte[] packet)
    {
        if (!ChecksumIsZero(packet, 0, 9))
        {
            Log.Trace($"[{Role}] header checksum mismatch, dropped");
            return;
        }

        var payloadLength = packet.Length - 10;
        if (payloadLength > 0 && !ChecksumIsZero(packet, 9, payloadLength + 1))
        {
            Log.Trace($"[{Role}] payload checksum mismatch, dropped");
            return;
        }

        var control = packet[4];
        var sequence = packet[5];
        var acknowledge = packet[6];
        var session = packet[7];
        var payload = payloadLength > 0 ? packet.AsSpan(9, payloadLength).ToArray() : null;

        Log.Trace($"[{Role}] rx ctrl=0x{control:X2} seq={sequence} ack={acknowledge} sid={session} len={Math.Max(0, payloadLength)}");

        if ((control & ControlReset) != 0)
        {
            Fail("the device reset the iAP2 link");
            return;
        }

        if ((control & ControlSync) != 0)
        {
            if (payload is not { Length: >= 10 } || payload[0] != 1)
            {
                Fail("invalid iAP2 sync parameters");
                return;
            }

            _maxOutgoing = Math.Clamp((int)payload[1], 1, 64);
            _maxLength = Math.Max(64, (payload[2] << 8) | payload[3]);
            _timeoutMs = Math.Max(50, (payload[4] << 8) | payload[5]);
            if (payload.Length > 8)
            {
                _maxRetries = Math.Max(1, (int)payload[8]);
            }

            _acknowledge = sequence;
            SendAck();
            Log.Trace($"[{Role}] sync: outgoing={_maxOutgoing} len={_maxLength} timeout={_timeoutMs}");
        }

        if ((control & ControlAck) != 0)
        {
            CompletePending(acknowledge);
            if (_state == LinkState.Negotiate)
            {
                _state = LinkState.Normal;
                Log.Trace($"[{Role}] link ready");
                Ready?.Invoke();
            }

            Flush();
        }

        if ((control & ControlRetransmit) != 0 && payload is not null)
        {
            foreach (var missing in payload)
            {
                var entry = _pending.FirstOrDefault(candidate => candidate.Sequence == missing);
                if (entry is not null)
                {
                    WritePacket(ControlAck, entry.Sequence, _acknowledge, entry.Session, entry.Data);
                }
            }

            return;
        }

        if ((control & ~ControlAck) == 0 && payload is not null)
        {
            AcceptSequence(sequence, session, payload);
        }
    }

    private void AcceptSequence(byte sequence, byte session, byte[] payload)
    {
        var distance = (byte)(sequence - _acknowledge);
        if (distance == 1)
        {
            _acknowledge = sequence;
            Deliver(session, payload);

            while (_outOfOrder.Remove((_acknowledge + 1) & 0xFF, out var next))
            {
                _acknowledge = (byte)(_acknowledge + 1);
                Deliver(next.Session, next.Data);
            }

            SendAck();
        }
        else if (distance is > 1 and < 128 && _outOfOrder.Count < 64)
        {
            _outOfOrder[sequence] = (session, payload);
            var missing = new List<byte>();
            for (var expected = _acknowledge + 1; expected != sequence; expected++)
            {
                missing.Add((byte)expected);
            }

            WritePacket(ControlRetransmit, _sequence, _acknowledge, 0, missing.ToArray());
        }
    }

    private void Deliver(byte session, byte[] payload)
    {
        if (session != SessionControl)
        {
            return;
        }

        if (_csmLength + payload.Length > _csmBuffer.Length)
        {
            Array.Resize(ref _csmBuffer, Math.Max(_csmBuffer.Length * 2, _csmLength + payload.Length));
        }

        Buffer.BlockCopy(payload, 0, _csmBuffer, _csmLength, payload.Length);
        _csmLength += payload.Length;

        while (_csmLength >= 6)
        {
            if (_csmBuffer[0] != 0x40 || _csmBuffer[1] != 0x40)
            {
                Fail("control session lost the 0x4040 marker");
                return;
            }

            var length = (_csmBuffer[2] << 8) | _csmBuffer[3];
            if (length < 6)
            {
                Fail($"invalid control message length {length}");
                return;
            }

            if (_csmLength < length)
            {
                return;
            }

            var messageId = (ushort)((_csmBuffer[4] << 8) | _csmBuffer[5]);
            Dictionary<int, byte[]> parameters;
            try
            {
                parameters = Csm.ParseParams(_csmBuffer, 6, length - 6);
            }
            catch (Exception ex)
            {
                Fail($"invalid control parameters: {ex.Message}");
                return;
            }

            Buffer.BlockCopy(_csmBuffer, length, _csmBuffer, 0, _csmLength - length);
            _csmLength -= length;
            CsmMessages++;
            CsmReceived?.Invoke(messageId, parameters);
        }
    }

    private void CompletePending(byte acknowledge) =>
        _pending.RemoveAll(entry => ((acknowledge - entry.Sequence) & 0xFF) < 128);

    private void SendAck() => WritePacket(ControlAck, _sequence, _acknowledge, 0, null);

    private void SendSync()
    {
        _lastSyncAt = Environment.TickCount64;
        WritePacket(ControlSync, _sequence, _acknowledge, 0, SyncPayload);
    }

    private void Flush()
    {
        if (_state != LinkState.Normal)
        {
            return;
        }

        while (_queue.Count > 0 && _pending.Count < _maxOutgoing)
        {
            var item = _queue.Dequeue();
            _sequence = (byte)(_sequence + 1);
            WritePacket(ControlAck, _sequence, _acknowledge, item.Session, item.Data);
            _pending.Add(new Pending(_sequence, item.Session, item.Data, Environment.TickCount64));
        }
    }

    private void WritePacket(byte control, byte sequence, byte acknowledge, byte session, byte[]? payload)
    {
        var length = payload is null ? 9 : payload.Length + 10;
        var packet = new byte[length];
        packet[0] = 0xFF;
        packet[1] = 0x5A;
        packet[2] = (byte)(length >> 8);
        packet[3] = (byte)length;
        packet[4] = control;
        packet[5] = sequence;
        packet[6] = acknowledge;
        packet[7] = session;
        packet[8] = Checksum(packet, 0, 8);

        if (payload is not null)
        {
            Buffer.BlockCopy(payload, 0, packet, 9, payload.Length);
            packet[^1] = Checksum(payload, 0, payload.Length);
        }

        WriteRaw(packet);
    }

    private void WriteRaw(byte[] data)
    {
        _transport.Write(data, 0, data.Length);
        BytesOut += data.Length;
    }

    private void Tick()
    {
        lock (_gate)
        {
            if (!_started || _state is LinkState.Dead || _disposed)
            {
                return;
            }

            var now = Environment.TickCount64;
            switch (_state)
            {
                case LinkState.Detect:
                    if (now - _startedAt > 15_000)
                    {
                        Fail("iAP2 detect timed out");
                        return;
                    }

                    if (_initiate && now - _lastMarkerAt > 1_000)
                    {
                        WriteRaw(Marker);
                        _lastMarkerAt = now;
                    }

                    break;

                case LinkState.Negotiate:
                    if (now - _startedAt > 20_000)
                    {
                        Fail("iAP2 negotiation timed out");
                        return;
                    }

                    if (now - _lastSyncAt > 500)
                    {
                        SendSync();
                    }

                    break;

                case LinkState.Normal:
                    foreach (var entry in _pending.ToArray())
                    {
                        if (now - entry.SentAt < _timeoutMs)
                        {
                            continue;
                        }

                        if (++entry.Retries > _maxRetries)
                        {
                            Fail("iAP2 acknowledge timed out");
                            return;
                        }

                        entry.SentAt = now;
                        WritePacket(ControlAck, entry.Sequence, _acknowledge, entry.Session, entry.Data);
                    }

                    break;
            }
        }
    }

    private void Fail(string reason)
    {
        lock (_gate)
        {
            if (_state == LinkState.Dead)
            {
                return;
            }

            _state = LinkState.Dead;
        }

        Failed?.Invoke(reason);
    }

    private void ConsumeRead(int count)
    {
        Buffer.BlockCopy(_readBuffer, count, _readBuffer, 0, _readLength - count);
        _readLength -= count;
    }

    private static byte Checksum(byte[] data, int offset, int count)
    {
        var sum = 0;
        for (var i = 0; i < count; i++)
        {
            sum += data[offset + i];
        }

        return (byte)-(sum & 0xFF);
    }

    private static bool ChecksumIsZero(byte[] data, int offset, int count)
    {
        var sum = 0;
        for (var i = 0; i < count; i++)
        {
            sum += data[offset + i];
        }

        return (sum & 0xFF) == 0;
    }

    private sealed class Pending
    {
        public Pending(byte sequence, byte session, byte[] data, long sentAt)
        {
            Sequence = sequence;
            Session = session;
            Data = data;
            SentAt = sentAt;
        }

        public byte Sequence { get; }

        public byte Session { get; }

        public byte[] Data { get; }

        public long SentAt { get; set; }

        public int Retries { get; set; }
    }

    private readonly record struct Queued(byte Session, byte[] Data);
}
