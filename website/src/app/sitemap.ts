import type { MetadataRoute } from "next";
import { site } from "@/config/site";
import { serviceHref, services } from "@/content/services";

export const dynamic = "force-static";

export default function sitemap(): MetadataRoute.Sitemap {
  const paths = [
    "/",
    ...services.map(serviceHref),
    "/pricing/",
    "/about/",
    "/resources/",
    "/resources/sf-1408-self-check/",
    "/contact/",
  ];
  return paths.map((p) => ({ url: `${site.url}${p}`, changeFrequency: "monthly", priority: p === "/" ? 1 : 0.7 }));
}
