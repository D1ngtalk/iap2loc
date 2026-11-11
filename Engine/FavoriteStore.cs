using System.Text.Json;

namespace Iap2Loc.Engine;

internal sealed record Favorite(
    string Id,
    string Name,
    double Latitude,
    double Longitude,
    double? Altitude,
    double? Accuracy);

/// <summary>Saved points, stored under %LOCALAPPDATA%\iap2loc\favorites.json.</summary>
internal sealed class FavoriteStore
{
    private readonly string _path;
    private readonly object _gate = new();
    private readonly List<Favorite> _items = new();

    public FavoriteStore()
    {
        var directory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "iap2loc");
        Directory.CreateDirectory(directory);
        _path = Path.Combine(directory, "favorites.json");
        Load();
    }

    public string FilePath => _path;

    public IReadOnlyList<Favorite> All
    {
        get
        {
            lock (_gate)
            {
                return _items.ToArray();
            }
        }
    }

    public Favorite Add(string? name, double latitude, double longitude, double? altitude, double? accuracy)
    {
        var label = string.IsNullOrWhiteSpace(name) ? $"{latitude:F4}, {longitude:F4}" : name.Trim();
        var favorite = new Favorite(
            Guid.NewGuid().ToString("N")[..8], label, latitude, longitude, altitude, accuracy);

        lock (_gate)
        {
            _items.Insert(0, favorite);
            if (_items.Count > 200)
            {
                _items.RemoveRange(200, _items.Count - 200);
            }

            Save();
        }

        return favorite;
    }

    public bool Remove(string id)
    {
        lock (_gate)
        {
            var removed = _items.RemoveAll(item => item.Id == id) > 0;
            if (removed)
            {
                Save();
            }

            return removed;
        }
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(_path))
            {
                return;
            }

            var items = JsonSerializer.Deserialize<List<Favorite>>(File.ReadAllText(_path));
            if (items is not null)
            {
                _items.AddRange(items);
            }
        }
        catch (Exception ex)
        {
            Log.Warn("收藏读取失败：" + ex.Message);
        }
    }

    private void Save()
    {
        try
        {
            File.WriteAllText(_path, JsonSerializer.Serialize(_items, new JsonSerializerOptions
            {
                WriteIndented = true,
                Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
            }));
        }
        catch (Exception ex)
        {
            Log.Warn("收藏保存失败：" + ex.Message);
        }
    }
}
