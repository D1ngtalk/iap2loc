using Iap2Loc.Protocol;
using Iap2Loc.Transport;

namespace Iap2Loc.Engine;

/// <summary>Owns every running session. All of them share one target, so the map moves them together.</summary>
internal sealed class SessionManager : IDisposable
{
    private readonly object _gate = new();
    private readonly List<RunSession> _sessions = new();
    private readonly string _profilePath;
    private int _nextId;

    public SessionManager(double latitude, double longitude, string? profilePath = null)
    {
        Target = new LocationTarget(latitude, longitude);
        _profilePath = string.IsNullOrWhiteSpace(profilePath) ? AccessoryProfile.DefaultPath : profilePath;
        Profile = AccessoryProfile.Load(_profilePath);
    }

    public LocationTarget Target { get; }

    /// <summary>Identity handed to new sessions. Existing sessions keep the one they started with.</summary>
    public AccessoryProfile Profile { get; private set; }

    public AccessoryProfile RotateIdentity(bool randomizeAdapter)
    {
        lock (_gate)
        {
            var next = Profile.Rotate();
            if (randomizeAdapter)
            {
                next = next with
                {
                    AdapterAddress = AccessoryProfile.RandomAdapterAddress(BluetoothDevices.TryGetLocalAdapterAddress()),
                };
            }

            next.Save(_profilePath);
            Profile = next;
            return next;
        }
    }

    public RunSession Add(RunRequest request)
    {
        RunSession session;
        lock (_gate)
        {
            session = new RunSession($"s{++_nextId}", request with { Profile = Profile }, Target);
            _sessions.Add(session);
        }

        // Connecting can block for seconds; never hold the manager lock while doing it.
        session.Start();
        return session;
    }

    public bool Remove(string id)
    {
        RunSession? session;
        lock (_gate)
        {
            session = _sessions.FirstOrDefault(candidate => candidate.Id == id);
            if (session is null)
            {
                return false;
            }

            _sessions.Remove(session);
        }

        session.Stop();
        session.Dispose();
        return true;
    }

    public void StopAll()
    {
        RunSession[] sessions;
        lock (_gate)
        {
            sessions = _sessions.ToArray();
            _sessions.Clear();
        }

        foreach (var session in sessions)
        {
            session.Stop();
            session.Dispose();
        }
    }

    public void ApplyTarget(TargetUpdate update, int? rate)
    {
        Target.Apply(update);

        if (rate is not { } interval)
        {
            return;
        }

        RunSession[] sessions;
        lock (_gate)
        {
            sessions = _sessions.ToArray();
        }

        foreach (var session in sessions)
        {
            session.SetInterval(interval);
        }
    }

    public object Snapshot()
    {
        RunSession[] sessions;
        lock (_gate)
        {
            sessions = _sessions.ToArray();
        }

        return new
        {
            warning = IdentitySource.Warning,
            identity = new
            {
                name = Profile.Name,
                model = Profile.Model,
                serial = Profile.Serial,
                adapter = Profile.AdapterAddress,
                path = _profilePath,
            },
            target = Target.Snapshot(),
            sessions = sessions.Select(session => new
            {
                id = session.Id,
                label = session.Label,
                transport = session.Request.Transport,
                address = session.Request.Transport.Equals("bt", StringComparison.OrdinalIgnoreCase)
                    ? session.Request.Mac
                    : $"{session.Request.Host}:{session.Request.Port}",
                running = session.Running,
                link = session.LinkState,
                authenticated = session.Authenticated,
                subscribed = session.Subscribed,
                sent = session.Sent,
                error = session.Error,
            }).ToArray(),
        };
    }

    public void Dispose() => StopAll();
}
