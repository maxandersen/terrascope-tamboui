# Terrascope on TamboUI

A proof-of-port: Terrascope (Python/`curses`) rebuilt on [TamboUI](https://github.com/tamboui/tamboui),
a Java TUI library. A braille world map with filled land, live aircraft,
earthquakes, and a day/night terminator — all on TamboUI's `Canvas` (vectors)
and `tamboui-image` `BrailleProtocol` (filled raster base).

## Run

Straight from GitHub via the jbang catalog alias — no clone needed:

```bash
jbang map@maxandersen/terrascope-tamboui          # (or terrascope@maxandersen/terrascope-tamboui)
```

Or from a clone:

```bash
cd app
jbang Terrascope.java                      # interactive
jbang -Dea Terrascope.java --dump 3 10 48  # headless: one frame to stdout (zoom lon lat)
```

Needs [jbang](https://jbang.dev) and TamboUI `0.7.0-SNAPSHOT` in your local Maven
repo (`./gradlew publishToMavenLocal` in the tamboui checkout). Natural Earth map
data downloads once to the temp dir and is cached.

### Live data & rate limits

All API responses (OpenSky, USGS, RainViewer index + tiles) are disk-cached under
`$TMPDIR/terrascope-cache` with a per-source TTL, and the last good copy is served
on a `429`/network error — so restarts don't re-hammer the endpoints.

OpenSky's anonymous flight API is heavily rate-limited (`429` when exhausted — the
flights label shows `flights(rate-limited)`). For reliable flights, set OAuth2
client credentials and they're used automatically:

```
export OPENSKY_CLIENT_ID=...
export OPENSKY_CLIENT_SECRET=...
```

Or drop an `opensky.json` in the working directory, `~`, or `~/.config/opensky/`:

```json
{ "clientId": "...", "clientSecret": "..." }
```

Get credentials at <https://opensky-network.org/my-opensky/account> → API Client.

### Keys

| key | action |
|-----|--------|
| arrows | pan |
| `+` / `-` | zoom |
| `0` | reset |
| `Tab` | cycle view presets (World / Flights / Weather / Seismic / Astro / All) |
| `i` | toggle info panel |
| `?` | toggle help modal |
| `a` | animate radar frames (loop the past hour) |
| `k` | toggle airports |
| `,` `.` | time slider — scrub radar frames (Weather) / move the terminator (Astro) |
| `/` | search planes by callsign / ICAO / country |
| scroll | pan · **Shift+scroll** zoom · drag pan |
| click | select nearest city or aircraft (details in the info panel) |
| `l` `o` `p` `c` `r` `f` `e` `n` | toggle land / coastline / provinces / cities / radar / flights / quakes / day-night |
| `q` | quit |

Limits, pan/zoom steps, data URLs and city population levels come from
Terrascope's `default.yaml` (override with `TERRASCOPE_CONFIG=/path/to.yaml`).

## How it maps to Terrascope

| TamboUI port (`app/`) | Terrascope original |
|-----------------------|---------------------|
| `View.java` | `core/view.py` + pan/zoom of `core/world.py` |
| `MapData.java` | `core/mapdata.py` |
| `Layer.java` | `core/layer.py` |
| `VectorLayers.java` | `layers/{flights,weather,daynight}` + basemap |
| `RadarLayer.java` | `layers/weather/radar.py` (RainViewer tiles) |
| `LandLayer.java` | land raster of `core/world.py` / `core/view.py` |
| `Astronomy.java` | `layers/daynight/astronomy.py` |
| `Config.java` | `core/config.py` + `assets/default.yaml` |

**Vector overlays** (coastline, provinces, cities, flights, quakes, day/night)
draw onto one shared braille `Canvas` `Context` in lon/lat space — so pan/zoom is
just changing the Canvas bounds, no re-rasterising.

**Filled land** is the one thing a stroke-only Canvas can't do: `LandLayer`
rasterises country polygons into a `BufferedImage` (`Graphics2D.fillPolygon`,
even-odd winding so lakes stay water), reduces it to a per-cell land fraction,
blurs that for soft coasts, and paints it as graded cell backgrounds *under* the
glyph overlays. **Radar** (`RadarLayer`) works the same way: Web-Mercator tiles
reprojected and sampled per cell into a background mosaic. Backgrounds are
applied after the Canvas because `Buffer.setStyle` patches the bg while keeping
each cell's glyph.

## Status

Feasibility, demonstrated end to end. Ported: base map, pan/zoom, coastlines,
provinces, cities + labels, flights, earthquakes, day/night terminator, filled
land with lakes (even-odd holes), weather radar (reprojected RainViewer tiles),
and config from `default.yaml`.

Ported chrome: a welcome splash, a tab bar of view presets (`Tabs`), a live
info panel (`Paragraph` sidebar), a help modal (`Clear` + centered `Block`), and
mouse-click city selection with a detail readout (SGR mouse parsed from the raw
backend; nearest city shown with country, population and coordinates).

Visual fidelity (Wave 1): directional aircraft arrows coloured by category,
airport triangles with IATA codes, country ISO labels at world zoom, filled
day/night shading with twilight bands, half-block + supersampled radar, a km
scale bar, and data attribution.

Tab info panels (Wave 2): CLOCK / SUN / MOON (astronomy), city weather +
multi-day forecast (Open-Meteo), and aircraft detail (callsign / altitude /
speed / heading / squawk). Click the map to select a city or aircraft.

Per-tab colour legends (bottom-right), a time slider (scrub radar frames on
Weather, drag the day/night terminator through time on Astro), and live plane
search/filter by callsign / ICAO / country.

The port now covers the Python app's features. Remaining polish: temperature
sparklines in the forecast, the exact RainViewer dBZ palette, and WASD pan
aliases. See the git history for the incremental build.
