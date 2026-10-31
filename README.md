# Payment Service

## Ansvar

Payment Service är en mikrotjänst i BlackMarqet-systemet som hanterar testbetalningar med Stripe Checkout.
Tjänsten skapar en betalningssession utifrån orderns sparade produkter, priser och antal.
Stripe-webhooks verifieras innan order-service meddelas om genomförd betalning eller utgången session.
Frontend anropar tjänsten via gatewayn. Interna endpoints skyddas med en gemensam tjänstenyckel.

## Tekniker

- Java 17
- Spring Boot v4
- Spring Security
- Maven
- Stripe Java SDK
- Stripe CLI för lokala webhooks

## Databas

Tjänsten har ingen egen databas. Orderuppgifter och betalningsstatus lagras i order-service.

## Miljövariabler

Miljövariabler måste vara satta för att tjänsten ska fungera korrekt.

- `STRIPE_SECRET_KEY` – Secret key från projektets Stripe-sandbox, börjar med `sk_test_`.
- `STRIPE_WEBHOOK_SECRET` – Webhooknyckeln från Stripe CLI, börjar med `whsec_`.
- `INTERNAL_SERVICE_TOKEN` – Gemensam hemlighet med minst 32 tecken, samma i gateway, payment-, order- och product-service.
- `ORDER_SERVICE_URL` – Order-services adress, normalt `http://localhost:8082`.
- `PAYMENT_RETURN_URL` – Frontendens retursida, normalt `http://localhost:5173/checkout/return`.

Nycklar anges i den lokala körmiljön och ska inte sparas i Git.

## Starta applikationen lokalt

Kräver Java 17 eller senare, Maven Wrapper, Stripe CLI och ett Stripe-konto med sandbox.
De berörda tjänsterna behöver använda grenarna med betalningsflödet.

1. Klona projektet och sätt miljövariablerna.
2. Starta auth-service, product-service, order-service och gateway. För ordermejl behövs även RabbitMQ och email-service.
3. Logga in med Stripe CLI och välj samma sandbox som testnyckeln tillhör:

```shell
stripe login
stripe listen --events "checkout.session.completed,checkout.session.expired" --forward-to http://localhost:8084/stripe/webhook
```

Lägg nyckeln som lyssnaren visar i `STRIPE_WEBHOOK_SECRET` och håll lyssnaren igång.

4. Starta applikationen från projektmappen:

**macOS/Linux:**

```bash
./mvnw spring-boot:run
```

**Windows PowerShell:**

```powershell
.\mvnw.cmd spring-boot:run
```

Tjänsten är tillgänglig på `http://localhost:8084`.
Frontend startas i sitt repository med `npm install` följt av `npm run dev`.
Testbetalning görs från varukorgen med kortnumret `4242 4242 4242 4242`, ett framtida datum och valfri tresiffrig CVC.
Retursidan visar betalningen som bekräftad först när backend har registrerat den som betald.

## Relaterade repositories

1. [Gateway](https://github.com/AnwarAsry/gateway-service)
2. [Auth Service](https://github.com/MartinGruter/auth-server-projekt2)
3. [Product Service](https://github.com/MartinGruter/product-service-projekt2)
4. [Order Service](https://github.com/MartinGruter/order-service-projekt2)
5. [Email Service](https://github.com/MartinGruter/email-service-projekt2)
6. [Frontend](https://github.com/MartinGruter/micro-services)
