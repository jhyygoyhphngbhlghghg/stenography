import { useEffect, useState } from "react";

// Start lightweight during SSR; load desktop graphics only after measuring.
export function useCompactPresentation() {
  const [compact, setCompact] = useState(true);
  useEffect(() => {
    const query = window.matchMedia("(max-width: 767px)");
    const update = () => setCompact(query.matches);
    update();
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return compact;
}
