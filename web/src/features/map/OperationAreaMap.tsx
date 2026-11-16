import { useMemo, useState } from 'react';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemText from '@mui/material/ListItemText';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import { Q } from '../../app/queryKeys';
import { usePermissions } from '../../app/session';
import { GksMap, type MapCircle, type MapMarker } from '../../components/map/GksMap';
import { formatLatLon } from '../../components/map/geo';
import { QueryState } from '../../components/QueryState';
import { useServices } from '../../services/registry';
import type { OperationView } from '../../services/types';
import { MAP_COLORS, MEETING_POINT_COLOR, PERSON_COLOR } from './mapModel';

/**
 * Operasyon sayfası haritası (ADR-025): bölge, buluşma noktaları ve yetkiliye gönüllülerin son konumları. Konumlar
 * yalnızca kullanıcı açıkça isteyince çekilir; her görüntüleme konum ucunda denetime yazılır (ADR-020).
 */
export function OperationAreaMap({ operation }: { operation: OperationView }) {
  const services = useServices();
  const perms = usePermissions();
  const opId = operation.id;
  const canLocations = perms.can('location.view', opId) && operation.state === 'ACTIVE';
  const [showPeople, setShowPeople] = useState(false);
  const map = useQuery({ queryKey: Q.mapOperation(opId), queryFn: () => services.map.operation(opId), staleTime: 30_000 });
  const locations = useQuery({
    queryKey: Q.locations(opId),
    queryFn: () => services.locations.list(opId, {}),
    enabled: canLocations && showPeople,
    staleTime: 15_000,
  });
  const people = useMemo(
    () => (showPeople && canLocations ? locations.data?.items ?? [] : []),
    [showPeople, canLocations, locations.data],
  );
  const area = map.data?.area ?? operation.area;
  const points = useMemo(() => map.data?.meetingPoints ?? [], [map.data]);

  const markers = useMemo<MapMarker[]>(
    () => [
      ...(area ? [{ id: 'center', lat: area.lat, lon: area.lon, color: MAP_COLORS[operation.state], label: `${operation.name} — merkez` }] : []),
      ...points.map((p) => ({ id: `mp-${p.groupId}`, lat: p.lat, lon: p.lon, color: MEETING_POINT_COLOR, label: `Buluşma noktası: ${p.groupName}` })),
      ...people.map((l) => ({ id: `u-${l.user.id}`, lat: l.lat, lon: l.lon, color: PERSON_COLOR, label: `Son konum: ${l.user.displayName}` })),
    ],
    [area, points, people, operation.name, operation.state],
  );
  const circles = useMemo<MapCircle[]>(
    () => (area?.radiusM ? [{ id: 'area', lat: area.lat, lon: area.lon, radiusM: area.radiusM, color: MAP_COLORS[operation.state] }] : []),
    [area, operation.state],
  );

  return (
    <Paper sx={{ p: 2 }} role="region" aria-label="Operasyon haritası">
      <Stack spacing={1}>
        <Typography variant="h6" component="h2">
          Harita
        </Typography>
        <QueryState isPending={map.isPending} error={map.error} onRetry={() => void map.refetch()}>
          {() => (
            <>
              <GksMap markers={markers} circles={circles} height={320} ariaLabel={`${operation.name} haritası`} />
              <List dense aria-label="Haritadaki noktalar">
                <ListItem>
                  <ListItemText
                    primary="Operasyon bölgesi"
                    secondary={area ? `${formatLatLon(area.lat, area.lon)}${area.radiusM ? ` · yarıçap ${area.radiusM} m` : ''}` : 'Belirtilmemiş'}
                  />
                </ListItem>
                {points.map((p) => (
                  <ListItem key={p.groupId}>
                    <ListItemText primary={`Buluşma noktası — ${p.groupName}`} secondary={`${p.address ? `${p.address} · ` : ''}${formatLatLon(p.lat, p.lon)}`} />
                  </ListItem>
                ))}
              </List>
            </>
          )}
        </QueryState>
        {canLocations && (
          <FormControlLabel
            control={<Checkbox checked={showPeople} onChange={(e) => setShowPeople(e.target.checked)} />}
            label="Gönüllülerin son konumlarını göster (görüntüleme denetim kaydına yazılır)"
          />
        )}
        {showPeople && locations.isSuccess && (
          <Typography variant="body2" color="text.secondary">
            Haritada {people.length} kişinin son konumu var. Ayrıntılı tablo: “Son konumlar”.
          </Typography>
        )}
      </Stack>
    </Paper>
  );
}
