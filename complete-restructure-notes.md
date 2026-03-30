# Ziel des Dokuments

Klar zu definieren, was das Programm eigentlich machen soll - aka den North Star des Programms definieren.
Auch soll hierdrin klar werden was überhaupt Spieler im Spiel machen und wie basierend darauf dann das Programm bestmöglich sie unterstützen kann.

# User Flow im Spiel

Der User durchlauft ja im Spiel selber mehrere States von dem was er macht - das soll hier aufgezeigt werden.

## Wiederholender Zyklus

Der folgende Zyklus wird dabei immer wieder wiederholt bis irgendwann das Spiel vorbei ist (Die Siegesbedingung wird erreicht):
1. Ich bin dran
2. Ich würfel
3. Ich bekomme Geld / gebe ab
4. Ich überlege mir was ich kaufen will
5. Ich kaufe ein Projekt / spare
6. Mein Zug endet
7. Ich bekomme potentiell Geld durch Blau Rot

## End- / Siegesbedingung des Spiels / Zykluses

Die Sigesbedingung ist, dass ein Spieler alle 4 Großprojekte gebaut hat.

## Ziel des Spielers während des Spielens

Auf der einen Seite ist es natürlich klar, dass der Spieler versucht zu gewinnen - aka die Win Condition zu erfüllen,
das ist das aller finalste Ziel des Spielers, ABER es gibt ja _beim_ spielen noch variierende Ziele.

Eines solches ist es die eigenen Münzen zu maximieren. Man will so viele Münzen wie möglich, weil man so sein eigenes Ziel vom Win erreicht.
Auf der gleichen Schiene versucht man die Münzen der anderen Spieler zu minimieren.
-> Aus diesen beiden Punkten ergibt sich, dass auf der einen Seite die Erwarteten Münzen Pro Runde für sich selber maximiert werden sollten, aber auf der anderen Seite versucht wird diesen EV und Co für alle anderen Spieler zu minimieren.

## Gedankengänge oder Fragen, was sich der Spieler wärend Schritt 4 im Zyklus denkt

- Was besitze ich? (-> meine longterm strategie)
- Was besitzen andere? (-> deren longterm strategien)
- Welche Projekte sind noch übrig zu kaufen? (-> was bring tmir was und was kann ich andern wegkaufen)
- Wie viel Geld habe ich?
- Wie viel Geld haben die anderen?
- Welche GPs habe ich, welche muss ich noch kaufen
  - In Hinsicht auf was die GPs dann mir selber bringen / was für einen Impact sie haben
  - Auch auf ob ich durch einen Kauf gewinnen kann oder so
- Welche GPs haben die andern?
  - das gleiche wie darüber
- Welche Würfelaugen sind für mich gut und welche haben noch Ausbaupotential (wenn ich sie würfeln würde)
- Wie kann ich langfristig am meisten Geld bekommen? -> Risk and Reward, was lohnt sich wo am meisten?

# Was muss das UI liefern?

Hiermit meine ich die exakten Komponenten die dann im UI sein sollten (als core functionality).
Sie sollten nicht durch andere Competing Features undermined werden, sondern nur supportet.
Das Ultimative Ziel für das UI ist es, dass schnellsmöglich der bestmögliche Nutzen aus den Komponenten gezogen werden kann.
Zu viele visuelle Einflüsse oder Dopplungen oder Contradicting Informations **müssen** vermieden werden.

- Turn Indicator - also wer gerade dran ist (flow zyklus 1)
  - vielleicht (also optional) auch die insgesamt turn reihenfolge (+ turn counter)
- Würfel Interface - womit die gewürfelten Würfel easy eingestellt werden können (flow zyklus 2)
- Münz Flow Indikator (für alles Spieler nach dem Wurf) (flow zyklus 3)
  - Unterstützend muss auch klar sein, wie viel geld zum Start der Runde da war, 
  wie viel nach dem Würfeln dar war und wie viel noch nach einem potentiellen kauf da wäre 
  (letzteres je nach design nur umsetztbar)
- Kauf Assistent, welcher alles aus dem aktuellen Spielstand (+daraus ergehende Kennzahlen/Metriken/Whatever) betrachtet,
damit die bestmögliche Entscheidung für einen Kauf vom Spieler getätigt werden kann
  - Er sollte Insights liefern und klar die Empfehlung erklären WARUM die Entscheidung so gut ist
  (mehrere Dimensionen in der Erklärung, sodass es zeigt, dass aus mehreren Perspektiven die Aktuelle Lage geschaut wurde
  und sich am Ende (wegen der aktuellen game-lage) für dieses Projekt nun entschieden wurde)
- Projekt Kaufen möglichkeit
  - Z.B. ein Kauf Knopf für jedes einzelne Projekt, welcher dann automatisch die Runde beendet
  und das Projekt dem Spieler zuschreibt
  und es dem Spieler das Geld dafür abzieht (wenn er es hat!)
  - Kann auch ein Dropdown sein von allen kaufbaren Projekten, kommt ganz darauf an, was dann am ende am besten passt
  - Hier muss auch berücksichtigt werden, dass klar sein sollte wie viele Münzen danach noch übrig bleiben

# Kauf Assistent Deep Dive

## Was definiere ich als "bestmögliche Entscheidung"?

Hier kann man schon mal nennen, dass die beste Entscheidung immer die ist, die es am Wahrscheinlichsten macht zu gewinnen UND die Wahrscheinliste wo man _nicht_ verliert (ganz wichtig). Am besten sollte sie dann auch die anderen Spieler daran mindern zu gewinnen oder sogar explizit deren Niederlage-Chancen erhöhen.

Auch nennenswert sind die Player Goals, welche durch die Entscheidung erfüllt werden sollten (Da so dann auch wieder dem erfüllen der Siegesbedingung angenährt wird).

## Weitere Interaktion Zwischen Programm User und Kauf Assistenten

Der Kauf Assistent wird ja wahrscheinlich Parameter haben für wie er seine Entscheidungen genau macht. Seien dies Gewichte für Priorisierung von Metriken oder Spielsituationsbewertungen, die Anzahl an simulierten Spielen oder auch weitere Suchparameter (Schnelligkeit/Akkurarität/Whatever) oder sogar Ausgabeeinstellungen (wie lang sollte explanation or whatever sein for example).

## Wichtige Metriken die mir so jetzt schon einfallen

Mit das wichtigste um nicht automatisch zu stagnieren oder zurückzufallen ist das Risiko von keinem Einkommen oder sogar negativen Einkommen (man muss wegen roten karten anderen Leuten was abdrücken).
-> Weil man schon immer mit dem "Hope for the Best, but expect the worst" slogan arbeiten sollte.

Auch kann schon mal Expected Coins in einer Runde (pro möglicher Würfelentscheideung! also ob 1d6 oder 2d6) genannt werden und die Erweiterung von ROI, obwohl diese ja sehr hopeful sind, weswegen trotzdem noch das Risk oder die Varianz or whatever drin sein sollte.
-> Auch hier kann je nach Spielsituation entschieden werden, ob doch mehr Risiko für höheren Reward gegangen wird um noch ein clutch comeback zu schaffen oder so.

## Wie der Spielbaum durchlaufen werden könnte

- Aus einer Startposition wird jede mögliche Würfelaugenkombination betrachtet (wegen bestimmten Gebäuden die dann bei anderen Kombis (wie einem Pasch) andere folgen haben type shit).
- Für jede dieser Dice Rolls gibt es auch die Wahrscheinlichkeit, dass dieser Knoten im Baum erreicht wird, zusammen mit den Cash Flows und den Risiken, Siegeswahrscheinlichkeiten, usw.
- von jedem dieser Würfelknoten kommt dann eine Aufspreizung in alle möglich kaufbaren Projekte nach dem Würfelwurf (basierend auch Player Coins)
- Die Projektknoten dienen dann als Start für den nächsten Spieler wo dann erneut komplett die Würfelknoten und folgenden Projektknoten durchlaufen werden.
- Heavy Pruning und Optimierung ist angesagt, da es sehr schnell ein sehr breiter Spielbaum werden kann
- Eventuell kann man auch optional sowas wie Monte Carlo Tree Search probieren und dann am Ende schauen was (a) schneller die Ergebnisse liefert und (b) welche der beiden Ansätze die besten Ergebnisse liefern.