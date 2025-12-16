(ns darelwasl.shared.block-types)

(def allowed-block-types
  "Canonical list of content block types used by backend validation and CLJS UI."
  [:hero :section :rich-text :feature :cta :list])

(def allowed-block-type-set (set allowed-block-types))
