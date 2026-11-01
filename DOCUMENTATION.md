# Betalningsfunktionen – genomförda ändringar

Betalningsfunktionen är ett individuellt, frivilligt tillägg till gruppens webbshop.
Funktionen är mergad i de personliga kopiornas `main` och använder endast Stripes testmiljö.

## 1. Payment-service

- Skapat en separat Spring Boot-tjänst på port 8084 och lagt till Stripe Java SDK.
- Konfigurerat Stripe-testnyckel, webhooknyckel och intern tjänstenyckel via miljövariabler.
- Skapat `PaymentConfig`, `PaymentController`, `CheckoutService` och `PaymentOrderClient`.
- Ersatt den första fasta testprodukten med orderns sparade produktnamn, priser och antal.
- Lagt till återanvändning av Stripe-session och idempotensnyckel vid återförsök.
- Skapat webhookhantering som verifierar signatur, testläge, session, belopp och valuta.
- Lagt till hämtning av betalningsstatus med kontroll av orderns ägare.
- Lagt till avbrytning som först verifierar och stänger Stripe-sessionen och sedan frigör lager via order-service.
- Hanterat återförsök och betalning som hinner slutföras under ett avbrott, utan att frigöra betalt lager.
- Bevarat 404 från order-service när betalningsordern saknas.
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
- Returnerat 404 för saknade betalningsordrar.
- Kompletterat tester för betalningsflöde, databas och säkerhet.

## 3. Product-service

- Skapat `StockReservation` med statusarna ACTIVE, RELEASED och CONSUMED.
- Sparat produktuppgifter och antal i reservationen.
- Skapat service, repository och interna endpoints för att reservera, återställa och förbruka lager.
- Lagt till kundkontroll, validering och databaslåsning vid lagerändringar.
- Förhindrat dubbla lagerändringar vid upprepade anrop och lagt till tester.

## 4. Gateway-service

- Skapat `PaymentGatewayController` och `PaymentGatewayService`.
- Lagt till endpoints för checkout, betalningsstatus och avbrytning.
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
- Lagt till hover-, klick- och laddningsåterkoppling på uppdateringsknappen.
- Förtydligat att ett tidigare betalningsförsök är separat från kundvagnens nuvarande innehåll.
- Lagt till **Cancel payment attempt**, som skickar ett autentiserat POST-anrop via gatewayn.
- Visat saknad order utan generell 500-sida och tillåtit rensning av enbart den gamla referensen.
- Behållit varukorgen tills backend bekräftar PAID; produkterna behålls vid utgången session.
- Lagt till tester och verifierat typkontroll och produktionsbygge.

## 6. Email-service och auth-service

- Ändrat valutan i ordermejlet från SEK till USD.
- Skapat `OrderConfirmationMessageTests` för mejlets formatering.
- Återanvänt auth-servicens befintliga autentisering utan betalningsspecifika kodändringar där.

## 7. Lokal konfiguration och driftsättning

- Skapat egna repositories och Docker Hub-images samt ett Stripe-konto med Microservices Webshop sandbox.
- Konfigurerat lokala miljövariabler; lokala webhooks kräver en aktiv Stripe CLI-lyssnare med egen signing secret.
- Driftsatt tjänsterna på Render och registrerat en publik HTTPS-webhook för samma sandbox som testnyckeln.
- Konfigurerat frontendens gatewayadress vid imagebygget och sessionshemlighet vid körning.
- Anpassat React Routers `allowedActionOrigins` till frontendens exakta Render-värd för formulär bakom HTTPS-proxyn.
- Säkerställt att den gemensamma interna token matchar mellan gateway, payment, order och product.
- Kompletterat betalningsrelaterade metoder i product-, order- och gateway-service med Javadoc med texten ”Ingår i betalningsflödet.”
- Felsökt en gammal Dockerimage och publicerat rätt main-version innan webhooken skickades om.

## 8. Verifiering

- Genomfört testbetalning med webbshoppens produkter, fått HTTP 200 vid omsänd webhook på Render och verifierat PAID för ordern.
- Verifierat hantering av saknad order och gammal pending-länk efter driftsättning.
- Testat avbrytning med automatiserade tester: ägarkontroll, upprepade anrop, Stripe-fel och betalning som hinner före avbrottet.
- Kört tester och typkontroll för payment-service, gateway och frontend.
- Testat avbrytningsknappen manuellt på Render och bekräftat att den fungerar korrekt.

