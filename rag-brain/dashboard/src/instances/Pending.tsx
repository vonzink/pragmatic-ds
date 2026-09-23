/**
 * A route that exists before its screen does.
 *
 * Task 1 of this phase lands the flag, the client and the routes; the screens arrive with the
 * tasks that build them. Routing to a placeholder rather than leaving the path unrouted keeps the
 * two separable: the route table can be asserted now, and a path that will exist later does not
 * spend the intervening tasks falling through the catch-all and looking like a bug.
 *
 * It says what it is. A blank panel would read as a screen that failed to load.
 */
export default function Pending({ screen }: { screen: string }) {
  return (
    <div className="card">
      <h1>{screen}</h1>
      <p className="muted">
        This screen is not built yet. The route and its data client are in place; the screen
        arrives with its own task in the dashboard phase.
      </p>
    </div>
  );
}
