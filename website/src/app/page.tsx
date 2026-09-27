import { AboutSection } from "@/components/sections/AboutSection";
import { ApproachBand } from "@/components/sections/ApproachBand";
import { ContactSection } from "@/components/sections/ContactSection";
import { Hero } from "@/components/sections/Hero";
import { ServiceRows } from "@/components/sections/ServiceRows";

/** Home: bone hero -> white services -> graphite approach -> bone about -> white contact. */
export default function HomePage() {
  return (
    <>
      <Hero />
      <ServiceRows />
      <ApproachBand />
      <AboutSection />
      <ContactSection />
    </>
  );
}
