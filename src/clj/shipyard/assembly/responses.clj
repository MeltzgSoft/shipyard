(ns shipyard.assembly.responses
  "Pure, recoverable draft errors for HTML and integration clients.")

(def messages
  {:invalid-name "Enter a ship name between 1 and 200 characters."
   :missing-loadout "This saved ship is unavailable. Refresh Ship Browser and choose another."
   :store-write-failed "The ship could not be saved. Check the data folder permissions and retry."
   :stale-revision "The draft changed. Review the current choices and try again."
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
   :socket-count "Author exactly one socket on this component that accepts the parent part's role."
   :invalid-plug "Reauthor this component's malformed plug."
   :invalid-socket "Reauthor this component's malformed socket."
   :invalid-frame "Reauthor the malformed mount frame."
   :invalid-orientation "Reauthor the malformed part orientation."
   :cycle "A component cannot contain itself or one of its ancestors."
   :no-draft "Choose a hull to begin."})
