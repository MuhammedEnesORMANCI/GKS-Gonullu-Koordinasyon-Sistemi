import { useEffect, useRef, useState, type ReactNode } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import type { Map as MlMap, Marker as MlMarker, MapMouseEvent } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { MAP_CONFIG } from '../../config/map';
import { bounds, circlePolygon } from './geo';

export interface MapMarker {
  id: string;
  lat: number;
  lon: number;
  /** CSS rengi (durum rengi; anlam yalnızca renkle verilmez, `label` da vardır). */
  color: string;
  /** Erişilebilir ad ve ipucu. */
  label: string;
}

export interface MapCircle {
  id: string;
  lat: number;
  lon: number;
  radiusM: number;
  color: string;
}

/** WebGL yoksa (eski tarayıcı, test ortamı) harita çizilmez; sayfalar aynı bilgiyi metin olarak da gösterir. */
export function mapSupported(): boolean {
  return typeof window !== 'undefined' && 'WebGLRenderingContext' in window;
}

/**
 * MapLibre GL tabanlı harita (ADR-025). Karo adresi `config/map.ts`; OSM atfı her haritanın altında görünür.
 * İşaretler yalnızca verilen verilerdir; harita kendi başına sunucuya istek atmaz (karolar dışında).
 */
export function GksMap({
  markers,
  circles = [],
  height = 360,
  ariaLabel,
  onMarkerClick,
  onPick,
  selectedId,
}: {
  markers: MapMarker[];
  circles?: MapCircle[];
  height?: number;
  ariaLabel: string;
  onMarkerClick?: (id: string) => void;
  /** Verilirse haritaya tıklayınca nokta seçilir (bölge/buluşma noktası seçimi). */
  onPick?: (lat: number, lon: number) => void;
  selectedId?: string;
}): ReactNode {
  const container = useRef<HTMLDivElement | null>(null);
  const map = useRef<MlMap | null>(null);
  const markerRefs = useRef<MlMarker[]>([]);
  const lib = useRef<typeof import('maplibre-gl') | null>(null);
  const callbacks = useRef({ onMarkerClick, onPick });
  callbacks.current = { onMarkerClick, onPick };
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(!mapSupported());
  const fitted = useRef(false);

  useEffect(() => {
    if (!mapSupported() || !container.current) return undefined;
    let cancelled = false;
    import('maplibre-gl')
      .then((maplibre) => {
        if (cancelled || !container.current) return;
        lib.current = maplibre;
        const instance = new maplibre.Map({
          container: container.current,
          style: {
            version: 8,
            sources: {
              base: { type: 'raster', tiles: [MAP_CONFIG.tileUrl], tileSize: 256, attribution: MAP_CONFIG.attribution, maxzoom: MAP_CONFIG.maxZoom },
            },
            layers: [{ id: 'base', type: 'raster', source: 'base' }],
          },
          center: [MAP_CONFIG.defaultCenter.lon, MAP_CONFIG.defaultCenter.lat],
          zoom: MAP_CONFIG.defaultZoom,
          attributionControl: { compact: false },
        });
        instance.addControl(new maplibre.NavigationControl({ showCompass: false }), 'top-right');
        instance.on('error', () => undefined);
        instance.on('click', (e: MapMouseEvent) => callbacks.current.onPick?.(e.lngLat.lat, e.lngLat.lng));
        instance.on('load', () => {
          instance.addSource('areas', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
          instance.addLayer({ id: 'areas-fill', type: 'fill', source: 'areas', paint: { 'fill-color': ['get', 'color'], 'fill-opacity': 0.12 } });
          instance.addLayer({ id: 'areas-line', type: 'line', source: 'areas', paint: { 'line-color': ['get', 'color'], 'line-width': 2 } });
          setReady(true);
        });
        map.current = instance;
      })
      .catch(() => setFailed(true));
    return () => {
      cancelled = true;
      markerRefs.current.forEach((m) => m.remove());
      markerRefs.current = [];
      map.current?.remove();
      map.current = null;
    };
  }, []);

  useEffect(() => {
    const instance = map.current;
    const maplibre = lib.current;
    if (!ready || !instance || !maplibre) return;
    markerRefs.current.forEach((m) => m.remove());
    markerRefs.current = markers.map((m) => {
      const marker = new maplibre.Marker({ color: m.color, scale: m.id === selectedId ? 1.3 : 1 })
        .setLngLat([m.lon, m.lat])
        .addTo(instance);
      const el = marker.getElement();
      el.setAttribute('aria-label', m.label);
      el.setAttribute('title', m.label);
      el.setAttribute('role', 'button');
      el.tabIndex = 0;
      el.style.cursor = 'pointer';
      const open = (ev: Event) => {
        ev.stopPropagation();
        callbacks.current.onMarkerClick?.(m.id);
      };
      el.addEventListener('click', open);
      el.addEventListener('keydown', (ev) => {
        if ((ev as KeyboardEvent).key === 'Enter') open(ev);
      });
      return marker;
    });
    const source = instance.getSource('areas') as import('maplibre-gl').GeoJSONSource | undefined;
    source?.setData({
      type: 'FeatureCollection',
      features: circles.map((c) => ({
        type: 'Feature',
        properties: { color: c.color },
        geometry: { type: 'Polygon', coordinates: [circlePolygon({ lat: c.lat, lon: c.lon }, c.radiusM)] },
      })),
    });
    if (!fitted.current) {
      const b = bounds(markers, circles.map((c) => ({ center: { lat: c.lat, lon: c.lon }, radiusM: c.radiusM })));
      if (b) {
        fitted.current = true;
        instance.fitBounds(b, { padding: 48, maxZoom: 15, duration: 0 });
      }
    }
  }, [ready, markers, circles, selectedId]);

  if (failed) {
    return (
      <Alert severity="info" role="status">
        Harita bu tarayıcıda gösterilemiyor; konum bilgileri aşağıda metin olarak verilmiştir.
      </Alert>
    );
  }
  return (
    <Box>
      <Box ref={container} role="region" aria-label={ariaLabel} sx={{ height, width: '100%', borderRadius: 1, overflow: 'hidden' }} />
      <Typography variant="caption" color="text.secondary">
        {MAP_CONFIG.attribution}
        {onPick ? ' · Konum seçmek için haritaya tıklayın.' : ''}
      </Typography>
    </Box>
  );
}
