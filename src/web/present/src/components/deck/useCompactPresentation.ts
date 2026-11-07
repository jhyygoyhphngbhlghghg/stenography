import { useEffect, useState } from "react";

/**
 * «Компактный» режим — это не только узкий экран. Телефон в альбомной
 * ориентации шире 767px, но выше всего 390px, и тяжёлая графика там съедает
 * ровно те строки, ради которых человек открыл ссылку. Поэтому режим считается
 * по обеим осям.
 */
const COMPACT_QUERY = "(max-width: 767px), (max-height: 560px)";

// Start lightweight during SSR; load desktop graphics only after measuring.
export function useCompactPresentation() {
  const [compact, setCompact] = useState(true);
  useEffect(() => {
    const query = window.matchMedia(COMPACT_QUERY);
    const update = () => setCompact(query.matches);
    update();
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return compact;
}
