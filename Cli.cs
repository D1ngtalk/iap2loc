using System.Globalization;

namespace Iap2Loc;

/// <summary>Very small command line parser: --key value, --flag, or --key=value.</summary>
internal sealed class Options
{
    private readonly Dictionary<string, string> _values = new(StringComparer.OrdinalIgnoreCase);

    public static Options Parse(string[] args)
    {
        var options = new Options();
        for (var i = 0; i < args.Length; i++)
        {
            var token = args[i];
            if (!IsFlag(token))
            {
                options.Positional.Add(token);
                continue;
            }

            var prefix = token.StartsWith("--", StringComparison.Ordinal) ? 2 : 1;
            var key = token[prefix..];
            var split = key.IndexOf('=');
            if (split >= 0)
            {
                options._values[key[..split]] = key[(split + 1)..];
                continue;
            }

            if (i + 1 < args.Length && !IsFlag(args[i + 1]))
            {
                options._values[key] = args[++i];
            }
            else
            {
                options._values[key] = "true";
            }
        }

        return options;
    }

    /// <summary>--name and -v are flags; -33.86 is a negative coordinate, not a switch.</summary>
    private static bool IsFlag(string token)
    {
        if (!token.StartsWith("-", StringComparison.Ordinal) || token.Length < 2)
        {
            return false;
        }

        if (token.StartsWith("--", StringComparison.Ordinal))
        {
            return true;
        }

        return !char.IsDigit(token[1]) && token[1] != '.';
    }

    public List<string> Positional { get; } = new();

    public bool Has(string key) => _values.ContainsKey(key);

    public bool Flag(string key) => _values.TryGetValue(key, out var value) && value is "true" or "1" or "yes";

    public string? Text(string key) => _values.TryGetValue(key, out var value) ? value : null;

    public string Text(string key, string fallback) => Text(key) ?? fallback;

    public int Int(string key, int fallback) =>
        Text(key) is { } raw && int.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out var value)
            ? value
            : fallback;

    public double Double(string key, double fallback) =>
        Text(key) is { } raw && double.TryParse(raw, NumberStyles.Float, CultureInfo.InvariantCulture, out var value)
            ? value
            : fallback;

    public double? NullableDouble(string key) =>
        Text(key) is { } raw && double.TryParse(raw, NumberStyles.Float, CultureInfo.InvariantCulture, out var value)
            ? value
            : null;
}

internal static class Log
{
    private static readonly object Gate = new();

    public static bool Verbose { get; set; }

    /// <summary>Every line, for the web UI. Handlers must not throw.</summary>
    public static event Action<DateTime, string, string>? Emitted;

    public static void Info(string message) => Write("info ", message, ConsoleColor.Gray);

    public static void Step(string message) => Write("step ", message, ConsoleColor.Cyan);

    public static void Warn(string message) => Write("warn ", message, ConsoleColor.Yellow);

    public static void Error(string message) => Write("error", message, ConsoleColor.Red);

    public static void Trace(string message)
    {
        if (Verbose)
        {
            Write("trace", message, ConsoleColor.DarkGray);
        }
    }

    public static void Ok(string message) => Write("ok   ", message, ConsoleColor.Green);

    private static void Write(string level, string message, ConsoleColor color)
    {
        var now = DateTime.Now;
        lock (Gate)
        {
            var previous = Console.ForegroundColor;
            try
            {
                Console.ForegroundColor = ConsoleColor.DarkGray;
                Console.Write(now.ToString("HH:mm:ss.fff", CultureInfo.InvariantCulture));
                Console.Write(' ');
                Console.ForegroundColor = color;
                Console.Write(level);
                Console.Write(' ');
                Console.ForegroundColor = previous;
                Console.WriteLine(message);
            }
            finally
            {
                Console.ForegroundColor = previous;
            }
        }

        try
        {
            Emitted?.Invoke(now, level.Trim(), message);
        }
        catch
        {
            // A broken log handler must never take the engine down.
        }
    }
}
