using System.Text;

namespace Iap2Loc.Protocol;

/// <summary>
/// iAP2 control-session messages: 40 40 | length(2) | message id(2) | parameters.
/// Each parameter is length(2) | id(2) | data, where length includes the four header bytes.
/// </summary>
internal static class Csm
{
    public const ushort Magic = 0x4040;

    public static byte[] Encode(ushort messageId, params byte[][] parameters)
    {
        var length = 6 + parameters.Sum(parameter => parameter.Length);
        var buffer = new byte[length];
        buffer[0] = 0x40;
        buffer[1] = 0x40;
        buffer[2] = (byte)(length >> 8);
        buffer[3] = (byte)length;
        buffer[4] = (byte)(messageId >> 8);
        buffer[5] = (byte)messageId;

        var offset = 6;
        foreach (var parameter in parameters)
        {
            Buffer.BlockCopy(parameter, 0, buffer, offset, parameter.Length);
            offset += parameter.Length;
        }

        return buffer;
    }

    public static byte[] Param(int id, byte[] data)
    {
        var length = data.Length + 4;
        var buffer = new byte[length];
        buffer[0] = (byte)(length >> 8);
        buffer[1] = (byte)length;
        buffer[2] = (byte)(id >> 8);
        buffer[3] = (byte)id;
        Buffer.BlockCopy(data, 0, buffer, 4, data.Length);
        return buffer;
    }

    /// <summary>Parses a parameter block into id → data. Later duplicates win, which is what iOS does.</summary>
    public static Dictionary<int, byte[]> ParseParams(byte[] buffer, int offset, int count)
    {
        var result = new Dictionary<int, byte[]>();
        var end = offset + count;
        while (offset + 4 <= end)
        {
            var length = (buffer[offset] << 8) | buffer[offset + 1];
            if (length < 4 || offset + length > end)
            {
                throw new InvalidDataException($"malformed iAP2 parameter at offset {offset} (length {length})");
            }

            var id = (buffer[offset + 2] << 8) | buffer[offset + 3];
            var data = new byte[length - 4];
            Buffer.BlockCopy(buffer, offset + 4, data, 0, data.Length);
            result[id] = data;
            offset += length;
        }

        return result;
    }
}

/// <summary>Fluent builder for iAP2 parameter blocks, including nested groups.</summary>
internal sealed class CsmParams
{
    private readonly MemoryStream _stream = new();

    public CsmParams Param(int id, byte[] data)
    {
        var encoded = Csm.Param(id, data);
        _stream.Write(encoded, 0, encoded.Length);
        return this;
    }

    public CsmParams U8(int id, byte value) => Param(id, new[] { value });

    public CsmParams U16(int id, ushort value) => Param(id, new[] { (byte)(value >> 8), (byte)value });

    /// <summary>iAP2 strings are NUL terminated, including the NMEA sentence payload.</summary>
    public CsmParams Str(int id, string value) => Param(id, Encoding.UTF8.GetBytes(value + "\0"));

    public CsmParams Void(int id) => Param(id, Array.Empty<byte>());

    public CsmParams U16List(int id, IEnumerable<ushort> values)
    {
        var list = values.ToArray();
        var data = new byte[list.Length * 2];
        for (var i = 0; i < list.Length; i++)
        {
            data[i * 2] = (byte)(list[i] >> 8);
            data[i * 2 + 1] = (byte)list[i];
        }

        return Param(id, data);
    }

    public CsmParams Group(int id, Action<CsmParams> body)
    {
        var nested = new CsmParams();
        body(nested);
        return Param(id, nested.ToArray());
    }

    public byte[] ToArray() => _stream.ToArray();
}
