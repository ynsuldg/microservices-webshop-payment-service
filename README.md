# Payment Service

## Ansvar

Payment Service är en mikrotjänst i Microservices Webshop som hanterar testbetalningar med Stripe Checkout.
Tjänsten skapar en betalningssession utifrån orderns sparade produkter, priser och antal.
Stripe-webhooks verifieras innan order-service meddelas om genomförd betalning eller utgången session.
Kunden kan även avbryta ett betalningsförsök; Stripe-sessionen stängs innan lagerreservationen frigörs.
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
- `STRIPE_WEBHOOK_SECRET` – Börjar med `whsec_`. Lokalt används Stripe CLI-lyssnarens hemlighet; på Render används den registrerade webhook-destinationens hemlighet.
- `INTERNAL_SERVICE_TOKEN` – Gemensam hemlighet med minst 32 tecken, samma i gateway, payment-, order- och product-service.
- `ORDER_SERVICE_URL` – Lokalt `http://localhost:8082`; på Render `https://microservices-webshop-order-service.onrender.com`.
- `PAYMENT_RETURN_URL` – Lokalt `http://localhost:5173/checkout/return`; på Render `https://microservices-webshop-frontend.onrender.com/checkout/return`.

Nycklar anges i körmiljön och ska inte sparas i Git. Testnyckeln och webhooken ska höra till samma Stripe-sandbox.

## Starta applikationen lokalt

Kräver Java 17 eller senare, Maven Wrapper, Stripe CLI och ett Stripe-konto med sandbox.
Betalningsflödet finns i de personliga kopiornas `main`.

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

## Driftsättning på Render

### Driftsatta tjänster

| Tjänst | Render-adress |
| --- | --- |
| Frontend – öppna webshoppen | [microservices-webshop-frontend.onrender.com](https://microservices-webshop-frontend.onrender.com) |
| Auth Service | [microservices-webshop-auth-service.onrender.com](https://microservices-webshop-auth-service.onrender.com) |
| Product Service | [microservices-webshop-product-service.onrender.com](https://microservices-webshop-product-service.onrender.com) |
| Order Service | [microservices-webshop-order-service.onrender.com](https://microservices-webshop-order-service.onrender.com) |
| Email Service | [microservices-webshop-email-service.onrender.com](https://microservices-webshop-email-service.onrender.com) |
| Gateway Service | [microservices-webshop-gateway-service.onrender.com](https://microservices-webshop-gateway-service.onrender.com) |
| Payment Service | [microservices-webshop-payment-service.onrender.com](https://microservices-webshop-payment-service.onrender.com) |

Backendadresserna är API:er och behöver inte visa en sida på `/`. Frontenden använder gatewayns `/gateway`.
Render Free kan ge väntetid när tjänster väcks. Email-service kan inte skicka SMTP via port 465/587 på Free.

### Konfiguration och uppdatering

Använd Docker-imagen `ynsuldg/microservices-webshop-payment-service:1.0.0` och sätt miljövariablerna i Render.
`ORDER_SERVICE_URL` ska peka på order-service på Render och `PAYMENT_RETURN_URL` på frontendens `/checkout/return`.

Skapa en webhook-destination i projektets **Microservices Webshop sandbox**:

- URL: `https://microservices-webshop-payment-service.onrender.com/stripe/webhook`
- Payload: Snapshot.
- Händelser: `checkout.session.completed` och `checkout.session.expired`.

Lägg destinationens signing secret i `STRIPE_WEBHOOK_SECRET` och driftsätt tjänsten igen.
Den lokala CLI-hemligheten ersätter inte denna hemlighet.
Efter en ny publicering på Docker Hub används **Deploy latest reference** i Render.
Kör alltid senaste `main`-versionens Actions-jobb; en äldre omkörning kan skriva över samma image-tagg med gammal kod.
Se [Render om färdiga images](https://render.com/docs/deploying-an-image) och [Free-begränsningar](https://render.com/docs/free).

## Betalningsstatus och avbrott

Att lämna Stripe Checkout eller ändra kundvagnen stänger inte betalningsförsöket.
På statussidan kan kunden välja **Cancel payment attempt**. Backend kontrollerar ägare, session och belopp,
stänger en öppen obetald Stripe-session och återställer sedan lagerreservationen.
Om betalningen redan har genomförts bekräftas den i stället. Vid ett osäkert svar behålls reservationen.
Ett försök utan sparad Stripe-session kan inte avbrytas på detta sätt; det behöver först återhämtas och kopplas till rätt session.
Kundvagnen behålls vid avbrott och töms efter PAID endast om den fortfarande motsvarar betalningsförsöket.

Pending-länken sparas i flikens `sessionStorage` och följer inte med till en annan dator.
En saknad order ger 404 från backend. Frontenden visar ett meddelande och låter kunden ta bort den gamla lokala referensen utan att tömma kundvagnen.

## Relaterade repositories

1. [Gateway](https://github.com/ynsuldg/microservices-webshop-gateway-service)
2. [Auth Service](https://github.com/ynsuldg/microservices-webshop-auth-service)
3. [Product Service](https://github.com/ynsuldg/microservices-webshop-product-service)
4. [Order Service](https://github.com/ynsuldg/microservices-webshop-order-service)
5. [Email Service](https://github.com/ynsuldg/microservices-webshop-email-service)
6. [Frontend](https://github.com/ynsuldg/microservices-webshop-frontend)
