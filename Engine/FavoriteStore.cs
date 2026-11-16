namespace Iap2Loc.Engine;

internal sealed record Favorite(
    string Id,
    string Name,
    double Latitude,
    double Longitude,
    double? Altitude,
    double? Accuracy);

/// <summary>Saved points, backed by the shared <see cref="AppConfig"/> file.</summary>
internal sealed class FavoriteStore
{
    private readonly AppConfig _config;

    public FavoriteStore(AppConfig config) => _config = config;

    public string FilePath => _config.FilePath;

    public IReadOnlyList<Favorite> All => _config.FavoritesSnapshot();

    public Favorite Add(string? name, double latitude, double longitude, double? altitude, double? accuracy) =>
        _config.AddFavorite(name, latitude, longitude, altitude, accuracy);

    public bool Remove(string id) => _config.RemoveFavorite(id);
}
