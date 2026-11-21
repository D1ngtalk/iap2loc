namespace Iap2Loc.Engine;

internal sealed record Favorite(
    string Id,
    string Name,
    double Latitude,
    double Longitude,
    double? Altitude,
    double? Accuracy);

internal sealed record SavedRoutePoint(
    double Latitude,
    double Longitude,
    double? Altitude = null,
    double? Time = null);

internal sealed record SavedRoute(
    string Id,
    string Name,
    double LengthMeters,
    double SpeedKmh,
    string LoopMode,
    bool AutoClose,
    List<SavedRoutePoint> Points);

/// <summary>Saved points and routes, backed by the shared <see cref="AppConfig"/> file.</summary>
internal sealed class FavoriteStore
{
    private readonly AppConfig _config;

    public FavoriteStore(AppConfig config) => _config = config;

    public string FilePath => _config.FilePath;

    public IReadOnlyList<Favorite> All => _config.FavoritesSnapshot();

    public IReadOnlyList<SavedRoute> AllRoutes => _config.SavedRoutesSnapshot();

    public Favorite Add(string? name, double latitude, double longitude, double? altitude, double? accuracy) =>
        _config.AddFavorite(name, latitude, longitude, altitude, accuracy);

    public bool Remove(string id) => _config.RemoveFavorite(id);

    public SavedRoute AddRoute(
        string? name,
        double lengthMeters,
        double speedKmh,
        string loopMode,
        bool autoClose,
        IReadOnlyList<SavedRoutePoint> points) =>
        _config.AddSavedRoute(name, lengthMeters, speedKmh, loopMode, autoClose, points);

    public bool RemoveRoute(string id) => _config.RemoveSavedRoute(id);
}
