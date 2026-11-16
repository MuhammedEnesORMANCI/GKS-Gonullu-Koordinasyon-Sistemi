import { useEffect, useMemo, useState } from 'react';
import Autocomplete from '@mui/material/Autocomplete';
import TextField from '@mui/material/TextField';
import { useInfiniteQuery } from '@tanstack/react-query';
import { messageFor } from '../api/errorMessages';
import type { Page, PageRequest, Uuid } from '../services/types';

export interface PersonOption {
  id: Uuid;
  displayName: string;
  fullName?: string;
}

export const PERSON_SEARCH_DEBOUNCE_MS = 300;
export const PERSON_PAGE_SIZE = 50;

/** Yazmayı bekleyip sorguyu geciktirir (her tuşta istek gitmesin). */
export function useDebounced<T>(value: T, ms = PERSON_SEARCH_DEBOUNCE_MS): T {
  const [current, setCurrent] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setCurrent(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return current;
}

/**
 * Sunucu tarafı arama + sayfalamalı çoklu kişi seçici (RAPOR_7 madde 3–4). Liste yalnızca yüklü öğelerde
 * süzülmez: her arama sunucuya gider; liste sonuna kaydırınca sonraki sayfa okunur. Seçilenler arama değişse de
 * çip olarak kalır.
 */
export function ServerPersonPicker({
  queryKey,
  fetchPage,
  label,
  value,
  onChange,
  helperText,
  disabled,
}: {
  queryKey: readonly unknown[];
  fetchPage: (query: string, page: PageRequest) => Promise<Page<PersonOption>>;
  label: string;
  value: PersonOption[];
  onChange: (people: PersonOption[]) => void;
  helperText?: string;
  disabled?: boolean;
}) {
  const [input, setInput] = useState('');
  const query = useDebounced(input.trim());
  const result = useInfiniteQuery({
    queryKey: [...queryKey, { q: query }],
    queryFn: ({ pageParam }) => fetchPage(query, { limit: PERSON_PAGE_SIZE, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor,
    enabled: !disabled,
  });
  const options = useMemo(() => {
    const byId = new Map<Uuid, PersonOption>();
    value.forEach((p) => byId.set(p.id, p));
    result.data?.pages.forEach((page) => page.items.forEach((p) => byId.set(p.id, p)));
    return [...byId.values()];
  }, [result.data, value]);

  return (
    <Autocomplete
      multiple
      disabled={disabled}
      options={options}
      value={value}
      filterOptions={(list) => list}
      getOptionLabel={(p) => (p.fullName && p.fullName !== p.displayName ? `${p.displayName} — ${p.fullName}` : p.displayName)}
      isOptionEqualToValue={(a, b) => a.id === b.id}
      onChange={(_, v) => onChange(v)}
      onInputChange={(_, v, reason) => {
        if (reason !== 'reset') setInput(v);
      }}
      loading={result.isFetching}
      noOptionsText={query ? 'Eşleşen kişi yok' : 'Kişi yok'}
      ListboxProps={{
        onScroll: (event) => {
          const el = event.currentTarget;
          if (el.scrollTop + el.clientHeight >= el.scrollHeight - 40 && result.hasNextPage && !result.isFetchingNextPage) {
            void result.fetchNextPage();
          }
        },
      }}
      renderInput={(params) => (
        <TextField
          {...params}
          label={label}
          error={!!result.error}
          helperText={result.error ? `Kişiler alınamadı: ${messageFor(result.error) ?? ''}` : helperText}
        />
      )}
    />
  );
}
