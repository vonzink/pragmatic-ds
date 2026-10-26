import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { ServicePage } from "@/components/ServicePage";
import { serviceDetails } from "@/content/serviceDetails";
import { services } from "@/content/services";

/** One static page per service, at the site root (e.g. /sf-1408-pre-award-accounting/). */
export const dynamicParams = false;

export function generateStaticParams() {
  return services.map((s) => ({ service: s.slug }));
}

export async function generateMetadata({ params }: PageProps<"/[service]">): Promise<Metadata> {
  const { service: slug } = await params;
  const s = services.find((x) => x.slug === slug);
  if (!s) return {};
  const d = serviceDetails[slug];
  return {
    title: d?.h1 ?? s.title,
    description: d?.lead ?? s.blurb,
    alternates: { canonical: `/${slug}/` },
  };
}

export default async function Page({ params }: PageProps<"/[service]">) {
  const { service: slug } = await params;
  const service = services.find((s) => s.slug === slug);
  if (!service) notFound();
  return <ServicePage service={service} detail={serviceDetails[slug]} />;
}
