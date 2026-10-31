# Betalningsfunktionen – genomförda ändringar

Betalningsfunktionen är ett individuellt, frivilligt tillägg till gruppens webbshop.
Arbetet ligger i separata grenar med namnet `feature/payment-flow` och använder endast Stripes testmiljö.

## 1. Payment-service

- Skapat en separat Spring Boot-tjänst på port 8084 och lagt till Stripe Java SDK.
- Konfigurerat Stripe-testnyckel, webhooknyckel och intern tjänstenyckel via miljövariabler.
- Skapat `PaymentConfig`, `PaymentController`, `CheckoutService` och `PaymentOrderClient`.
- Ersatt den första fasta testprodukten med orderns sparade produktnamn, priser och antal.
- Lagt till återanvändning av Stripe-session och idempotensnyckel vid återförsök.
- Skapat webhookhantering som verifierar signatur, testläge, session, belopp och valuta.
- Lagt till hämtning av betalningsstatus med kontroll av orderns ägare.
- Lagt till tester, svenska metodkommentarer, README och Dockerfile.

## 2. Order-service

- Skapat `OrderStatus`: CREATED, PENDING_PAYMENT, PAID och CANCELLED.
- Utökat `Order` med Stripe-session, reservations-id, tidpunkt och betalningsflaggor.
- Lagt till produkt-id på `OrderItem`.
- Skapat `PaymentOrderService`, interna endpoints och kommunikation med lagerreservationerna.
- Lagt till kontroller av kund, session, orderstatus, belopp och valuta.
- Kopplat bekräftad betalning till lagerförbrukning och ordermeddelande via RabbitMQ.
- Lagt till återförsök för väntande ordermeddelanden utan att förbruka lagret igen.
- Lagt till databasstandardvärdet `false` för `stockReserved` och `confirmationPending` för befintliga ordrar.
- Justerat felhanteringen så att serverfel inte döljs bakom ett missvisande 401-svar.
- Kompletterat tester för betalningsflöde, databas och säkerhet.

## 3. Product-service

- Skapat `StockReservation` med statusarna ACTIVE, RELEASED och CONSUMED.
- Sparat produktuppgifter och antal i reservationen.
- Skapat service, repository och interna endpoints för att reservera, återställa och förbruka lager.
- Lagt till kundkontroll, validering och databaslåsning vid lagerändringar.
- Förhindrat dubbla lagerändringar vid upprepade anrop och lagt till tester.

## 4. Gateway-service

- Skapat `PaymentGatewayController` och `PaymentGatewayService`.
- Lagt till endpoints för checkout och betalningsstatus.
- Verifierat kundens JWT genom auth-servicens befintliga `/auth/me`.
- Hämtat kundnamnet från den verifierade användaren och skickat produkt-id och antal vidare.
- Konfigurerat payment-servicens adress, intern tjänstenyckel, felhantering och tester.

Interna endpoints i payment-, order- och product-service skyddas med samma
`INTERNAL_SERVICE_TOKEN`, som gatewayn använder för betalningsanropen.
Hemligheten skickas aldrig till frontend.

## 5. Frontend

- Skapat `paymentService` och ändrat varukorgens knapp till **Pay with Stripe**.
- Kopplat checkout till gatewayn och vidare till Stripe.
- Sparat betalningsförsökets id i sessionStorage för säkra återförsök.
- Förhindrat ett nytt försök med ändrad varukorg medan tidigare betalning väntar.
- Lagt till vänteläge, felmeddelanden och en retursida med betalningsstatus och uppdateringsknapp.
- Behållit varukorgen tills backend bekräftar PAID; produkterna behålls vid utgången session.
- Lagt till tester och verifierat typkontroll och produktionsbygge.

## 6. Email-service och auth-service

- Ändrat valutan i ordermejlet från SEK till USD.
- Skapat `OrderConfirmationMessageTests` för mejlets formatering.
- Återanvänt auth-servicens befintliga autentisering utan betalningsspecifika kodändringar där.

## 7. Lokal konfiguration och verifiering

- Skapat ett separat Stripe-konto och BlackMarqet sandbox för webbshoppen.
- Konfigurerat miljövariablerna och anslutit Stripe CLI till rätt sandbox.
- Genomfört en testbetalning med webbshoppens produkter och verifierat status PAID.
- Återställt den första betalningsbekräftelsen efter kontroll hos Stripe, eftersom webhooklyssnaren hade stannat.
- Startat webhooklyssnaren som separat bakgrundsprocess. Automatisk webhookbekräftelse behöver verifieras med ett nytt testköp.
- Byggt payment-servicens JAR och Dockerimage lokalt.

