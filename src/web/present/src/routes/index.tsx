import { createFileRoute } from "@tanstack/react-router";
import { Deck } from "@/components/deck/Deck";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "Командная презентация — живой показ" },
      {
        name: "description",
        content: "Интерактивная презентация команды с автопереходами и живым распознаванием речи.",
      },
      { property: "og:title", content: "Командная презентация — живой показ" },
      {
        property: "og:description",
        content: "Интерактивная презентация команды с автопереходами и живым распознаванием речи.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: Deck,
});
