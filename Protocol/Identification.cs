namespace Iap2Loc.Protocol;

internal sealed record AccessoryIdentity(
    string Name,
    string Model,
    string Manufacturer,
    string Serial,
    string Firmware,
    string Hardware);

/// <summary>
/// Builds the IdentificationInformation (0x1D01) payload of a plain MFi GPS accessory.
///
/// The two lists that matter:
///   parameter 6  messages this accessory sends      -> must contain 0xFFFB LocationInformation
///   parameter 7  messages this accessory receives   -> must contain 0xFFFA / 0xFFFC
///   group 22     the Location component declaration, without it iOS never subscribes
/// </summary>
internal static class Identification
{
    private static readonly ushort[] CoreSent =
    {
        0xAA01, // AuthenticationCertificate
        0xAA03, // AuthenticationResponse
        0xFFFB, // LocationInformation
    };

    private static readonly ushort[] CoreReceived =
    {
        0xAA00, // RequestAuthenticationCertificate
        0xAA02, // RequestAuthenticationChallengeResponse
        0xAA04, // AuthenticationFailed
        0xAA05, // AuthenticationSucceeded
        0xFFFA, // StartLocationInformation
        0xFFFC, // StopLocationInformation
    };

    public static byte[] Build(AccessoryIdentity identity, byte[]? bluetoothAdapter)
    {
        var parameters = new CsmParams()
            .Str(0, identity.Name)
            .Str(1, identity.Model)
            .Str(2, identity.Manufacturer)
            .Str(3, identity.Serial)
            .Str(4, identity.Firmware)
            .Str(5, identity.Hardware)
            .U16List(6, CoreSent)
            .U16List(7, CoreReceived)
            .U8(8, 0)   // no USB power source
            .U16(9, 20); // carried over from the working implementations, semantics undocumented

        parameters
            .Str(12, "en")
            .Str(13, "en");

        // Parameter 17 is the Bluetooth transport component. A transport component is mandatory:
        // omitting it makes iOS reject the whole identification with 0x1D03 (offending: 17).
        if (bluetoothAdapter is { Length: 6 })
        {
            parameters.Group(17, transport =>
            {
                transport.U16(0, 0);              // component id
                transport.Str(1, "blue");         // component name
                transport.Void(2);
                transport.Param(3, bluetoothAdapter); // local adapter address, raw 6 bytes
                transport.Str(4, "blue");         // transport type
                transport.Void(5);
            });
        }

        parameters
            .Group(22, component =>
            {
                component.U16(0, 0);
                component.Str(1, identity.Name);
                component.Void(17);
                component.Void(18);
            });

        return parameters.ToArray();
    }
}
