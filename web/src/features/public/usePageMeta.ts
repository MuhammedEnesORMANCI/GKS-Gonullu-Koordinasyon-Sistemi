import { useEffect } from 'react';
import { pageTitle } from '../../config/brand';

/** SEO: sayfa başlığı ve meta açıklaması (yoksa oluşturulur). */
export function usePageMeta(title: string | undefined, description: string) {
  useEffect(() => {
    document.title = pageTitle(title);
    let meta = document.head.querySelector<HTMLMetaElement>('meta[name="description"]');
    if (!meta) {
      meta = document.createElement('meta');
      meta.name = 'description';
      document.head.appendChild(meta);
    }
    meta.content = description;
  }, [title, description]);
}
