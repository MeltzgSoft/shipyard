(ns shipyard.assembly.responses
  "Pure, recoverable draft errors for HTML and integration clients.")

(def messages
  {:stale-revision "The draft changed. Review the current choices and try again."
   :stale-slot "That slot no longer exists. Review the current assembly."
   :stale-draft "An assigned part or mount changed. Clear the affected slot or start again."
   :library-changed "The library changed. Choose a hull again or reset the draft."
   :incompatible-role "That part's authored role is not accepted by this socket."
   :unauthored-role "Author the part's role before using it in an assembly."
   :unauthored-hull "Choose a manually authored hull or hull section."
   :unavailable-mesh "The unsupported source mesh is unavailable. Restore it or choose another part."
   :missing-part "That part is no longer in the library."
   :different-bundle "Parts must belong to the hull's bundle."
   :different-class "Parts must belong to the hull's class."
   :plug-count "Author exactly one plug on this component."
   :invalid-plug "Reauthor this component's malformed plug."
   :invalid-frame "Reauthor the malformed mount frame."
   :invalid-orientation "Reauthor the malformed part orientation."
   :cycle "A component cannot contain itself or one of its ancestors."
   :no-draft "Choose a hull to begin."})
