# BillGuard

Find every recurring charge on your bank account, and keep it on your own infrastructure.

BillGuard connects to your bank through Plaid, works out which charges repeat, and
sorts them into subscriptions you can cancel, bills you can renegotiate, and fixed
costs you can only watch. Rent and loan payments are included, so the monthly total
is what you actually owe rather than a partial view.

## What it does

- **Finds recurring charges** from up to two years of transaction history, including
  ones you signed up for and forgot
- **Separates subscriptions from bills** so the list matches what you can act on
- **Tracks price changes** and flags a stream whose amount moved
- **Learns from corrections**: mark something as recurring or not, or change its
  category, and later scans leave your decision alone
- **Never guesses quietly**: every charge shows how much evidence sits behind it

## Why self-hosted

BillGuard runs on your own machine, with your own Plaid credentials and your own
database. No transaction data reaches a service anyone else operates.

Classification runs locally too, on a small [Ollama](https://ollama.com) model, so
the app works with no API key and no outbound model calls. If the model is
unavailable, detection falls back to deterministic rules rather than failing.

Recurrence itself is arithmetic, not inference: BillGuard measures the gaps between
charges and how steady the amounts are. The model is used only where a judgement is
genuinely needed - recognising an unfamiliar merchant and placing it in a category.

## Run it

Requires Docker, an [Auth0](https://auth0.com) tenant, and a
[Plaid](https://plaid.com) account. Both have free tiers that cover personal use.

```powershell
Copy-Item .env.example .env
Copy-Item web-app/.env.example web-app/.env.local
# Fill in the Auth0 and Plaid values in both files.
# Generate TOKEN_ENCRYPTION_KEY with: openssl rand -base64 32

docker compose up --build
```

Then start the web app:

```powershell
Set-Location web-app
npm ci
npm run dev
```

Open http://localhost:5173. Plaid's Sandbox environment is the default, so you can
connect a test bank with fixture data before pointing it at a real account.

Full setup, including how to create the Auth0 API and application, is in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Status

Bank connection, transaction sync, and recurring-charge detection work today.
Cancellation assistance, alerts, and a subscribable calendar feed are planned -
see [PHASES.md](PHASES.md).

Built with Java 25 and Spring Boot, Python and FastAPI, SvelteKit, PostgreSQL, and
Kubernetes.

## License

MIT. See [LICENSE](LICENSE).
