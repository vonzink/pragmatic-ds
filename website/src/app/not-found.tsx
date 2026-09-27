import { Button } from "@/components/ui/Button";
import { Container } from "@/components/ui/Container";

export const metadata = { title: "Page not found" };

export default function NotFound() {
  return (
    <section className="py-14 md:py-20">
      <Container>
        <p className="eyebrow mb-4 text-muted">404</p>
        <h1 className="text-[30px] leading-[1.08] font-semibold tracking-[-0.025em] md:text-[44px]">
          That page isn&apos;t here.
        </h1>
        <p className="mt-4 max-w-lead text-base leading-[1.55] md:text-lg">
          The link may be old or mistyped. Head back to the home page to find what you need.
        </p>
        <Button href="/" className="mt-8">
          Back to home
        </Button>
      </Container>
    </section>
  );
}
