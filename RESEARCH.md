# Undersökning – Stripe-betalning för webbshoppen

## Syfte och avgränsning

Undersöka vad som behövs för att lägga till engångsbetalningar i webbshoppen.
Lösningen avgränsas till kortbetalningar i Stripes sandbox med USD som valuta.
Arbetet är ett individuellt, frivilligt tillägg. Genomförda ändringar beskrivs i [DOCUMENTATION.md](DOCUMENTATION.md).

## Val av betalningslösning

Stripe Checkout ger en färdig betalningssida. Backend skapar en Checkout Session och
frontend skickar kunden till sessionens adress. Kunden anger kortuppgifter på Stripes sida.
Det passar projektet eftersom vi slipper bygga ett eget kortformulär.
Se [Stripes beskrivning av Checkout](https://docs.stripe.com/payments/checkout/how-checkout-works?payment-ui=stripe-hosted).

En separat payment-service och lagerreservationer är våra arkitekturval för webbshoppen.
De är inte generella krav för att använda Stripe.

## Vad som behövs

- En Stripe-sandbox och en hemlig testnyckel som backend använder.
- Stripe Java SDK i payment-service för att skapa och hämta betalningssessioner.
- Produktnamn, antal, valuta och priser från backend när sessionen skapas. Frontendens priser får inte styra betalningsbeloppet.
- En koppling mellan sparad order och Stripe-session samt returadresser till webbshoppen.
- En webhook som verifierar Stripes signatur med rå begärandekropp och en separat webhooknyckel.
- Kontroll av betalningsstatus, orderkoppling, belopp och valuta innan ordern markeras som betald.
- Återanvändning av betalningsförsök och skydd mot upprepad behandling av samma betalning.

Retursidan räcker inte som betalningsbekräftelse: kunden kan stänga webbläsaren innan återkomsten.
Bekräftelsen behöver därför hanteras på serversidan genom webhooken.
Se [Stripes vägledning om orderhantering](https://docs.stripe.com/checkout/fulfillment?ui=full-page)
och [webhookverifiering](https://docs.stripe.com/events/manage-webhook-endpoints#signature-errors).

En idempotensnyckel gör att ett återförsök kan återanvända samma skapandeanrop hos Stripe.
Order- och lagerhanteringen behöver dessutom egna kontroller mot dubbla ändringar.
Se [Stripes dokumentation om idempotens](https://docs.stripe.com/api/idempotent_requests).

## Betalningsflöde

1. Kunden väljer produkter och startar betalningen i varukorgen.
2. Gatewayn verifierar kunden. Backend sparar ordern och reserverar lagret.
3. Payment-service skapar eller återanvänder Stripe-sessionen utifrån orderns sparade uppgifter.
4. Kunden betalar på Stripes Checkout-sida.
5. Webhooken verifieras och sessionens aktuella status kontrolleras hos Stripe.
6. Vid bekräftad betalning markeras ordern PAID, lagerreservationen förbrukas och ordermeddelandet skickas.
7. Frontenden hämtar backendens status och tömmer motsvarande, oförändrade varukorg efter bekräftelse.

Om kunden lämnar Checkout behålls varukorgen. Lager återställs först när sessionen
har bekräftats som utgången och obetald.

## Avbrytning av betalningsförsök

Kundvagnen och en redan skapad betalningssession är separata. Att ta bort en produkt eller
följa Stripes returlänk får därför inte i sig frigöra reserverat lager.

Ett uttryckligt avbrott går via gatewayn med verifierad kundidentitet. Payment-service kontrollerar
orderns ägare och sessionskoppling och använder Stripes expire-anrop för en öppen obetald session.
Först efter bekräftad utgång frigörs reservationen. Om betalningen hann slutföras bekräftas ordern i stället.
Vid nätverksfel behålls betalningsreferensen för ett säkert återförsök.
Se [Stripes dokumentation om att stänga Checkout-sessioner](https://docs.stripe.com/api/checkout/sessions/expire).

## Berörda delar i projektet

| Del | Anpassning som behövs för vårt flöde |
| --- | --- |
| Payment-service | Stripe-klient, checkout, webhook, statuskontroll och säker avbrytning. |
| Order-service | Betalningsstatus, sessionskoppling och bekräftelse av ordern. |
| Product-service | Reservera lager, förbruka reservation eller återställa lager. |
| Gateway | Verifiera kunden och vidarebefordra betalnings- och avbrottsanrop. |
| Frontend | Betalknapp, omdirigering, felhantering, retursida och avbrytningsknapp. |
| Email-service | Samma valuta som betalningen i orderbekräftelsen. |

## Lokal testmiljö

Testnyckel och webhooknyckel ska tillhöra samma sandbox och anges som miljövariabler.
Vårt interna API använder även en gemensam tjänstenyckel. Payment-service behöver ingen egen databas;
order-service lagrar order och betalningsstatus.

Stripe CLI vidarebefordrar webhooks till den lokala tjänsten och måste hållas igång.
Testa lyckad och nekad betalning, avbruten checkout, återförsök och upprepade webhookanrop.
Stripes testkort `4242 4242 4242 4242` kan användas med ett framtida datum och tresiffrig CVC.
Se [Stripes testinstruktioner](https://docs.stripe.com/testing).

## Driftsatt testmiljö

På Render används tjänsternas publika adresser och en registrerad HTTPS-webhook med
destinationens egen signing secret. Den är separat från en lokal CLI-lyssnares hemlighet.
En testbetalning har verifierats som PAID efter lyckad webhookleverans på Render.
Avbrytningen har också testats manuellt på Render och fungerar korrekt.
Pending-länken är lokal för webbläsarfliken; den är inte en kontobaserad orderhistorik.

## Öppna frågor och nästa steg

- Återhämtning av försök som saknar sparad Stripe-session kräver fortsatt utredning. Sådana försök frisläpps inte av avbrytningsknappen.
- Lokalt och Render delar för närvarande databas; separata databaser skulle isolera testordrar och lagerreservationer mellan miljöerna.
- Orderstatus och RabbitMQ-publicering är inte atomiska; ytterligare hantering behövs om dubbla mejl vid avbrott måste förhindras.
