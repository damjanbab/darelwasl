(ns darelwasl.features.users
  (:require [clojure.string :as str]
            [darelwasl.ui.components :as ui]
            [darelwasl.ui.shell :as shell]
            [re-frame.core :as rf]))

(def ^:private role-options
  [{:id :role/admin :label "Admin" :note "Full access"}
   {:id :role/content-editor :label "Content editor" :note "Manage website content"}
   {:id :role/file-library :label "File library" :note "Upload/delete files"}
   {:id :role/betting-engineer :label "Betting" :note "Access betting tools"}
   {:id :role/codex-terminal :label "Terminal" :note "Access Codex sessions"}])

(defn- role-label
  [role]
  (or (some->> role-options
               (some (fn [{:keys [id label]}]
                       (when (= id role) label))))
      (-> role name (str/replace #"-" " ") str/capitalize)))

(defn- roles-summary
  [roles]
  (if (seq roles)
    (str/join " · " (map role-label (sort roles)))
    "No roles"))

(defn- user-row
  [user selected?]
  (let [username (:user/username user)
        name (:user/name user)
        roles (set (:user/roles user))
        title (or name username "Unknown user")
        meta (if (and name username) username "—")
        description (roles-summary roles)]
    [ui/list-row {:title title
                  :meta meta
                  :description description
                  :selected? selected?
                  :on-click #(rf/dispatch [:darelwasl.app/select-user (:user/id user)])}]))

(defn- list-panel
  []
  (let [{:keys [items status error selected]} @(rf/subscribe [:darelwasl.app/users])]
    [ui/entity-list {:title "Users"
                     :meta (str (count items) " users")
                     :items items
                     :status status
                     :error error
                     :selected selected
                     :key-fn :user/id
                     :render-row user-row
                     :panel-class "users-list"
                     :list-class "users-list-items"
                     :header-actions [[ui/button {:variant :secondary
                                                  :disabled (= status :loading)
                                                  :on-click #(rf/dispatch [:darelwasl.app/fetch-users])}
                                       "Refresh"]]}]))

(defn- role-picker
  [selected-roles]
  [:div.field-group
   [:div.meta "Roles"]
   [:div.users-role-grid
    (for [{:keys [id label note]} role-options]
      (let [checked? (contains? selected-roles id)]
        ^{:key (str "role-" (name id))}
        [:label.checkbox.users-role
         [:input {:type "checkbox"
                  :checked checked?
                  :aria-label (str "Toggle role " label)
                  :on-change #(rf/dispatch [:darelwasl.app/toggle-user-role id])}]
         [:span
          [:strong label]
          (when note [:span.meta (str " · " note)])]]))]])

(defn- detail-shell
  [{:keys [title new-label save-label on-new on-save mode error status has-id? deleting? saving? on-delete disable-delete?]} & body]
  (let [footer [:div.button-row
                [ui/button {:type "submit"
                            :disabled saving?}
                 save-label]
                (when (= mode :edit)
                  [ui/button {:variant :danger
                              :on-click on-delete
                              :disabled (or deleting? disable-delete?)}
                   "Delete"])]]
    [:div.panel.users-detail
     [:div.section-header
      [:div
       [:h2 title]
       (when (= mode :edit)
         [:span.meta "Manage account access"])]
      [:div.controls
       [ui/button {:variant :secondary
                   :on-click on-new}
        new-label]]]
     (if (and (= mode :edit) (not has-id?))
       [:div.state.empty
        [:strong "Select a user"]
        [:p "Pick a user from the list or create a new one."]]
       (into
        [:form.detail-form {:on-submit (fn [e] (.preventDefault e) (on-save))}
         (when error [:div.form-error {:role "alert"} error])
         (when (= status :success) [:div.form-success {:aria-live "polite"} "Saved"])]
        (concat body [footer])))]))

(defn- detail-panel
  []
  (let [{:keys [detail items]} @(rf/subscribe [:darelwasl.app/users])
        session @(rf/subscribe [:darelwasl.app/session])
        {:keys [form mode status error]} detail
        saving? (= status :saving)
        deleting? (= status :deleting)
        selected-id (:id form)
        selected (some #(when (= (:user/id %) selected-id) %) items)
        is-self? (= selected-id (get-in session [:user :id]))
        roles (or (:roles form) #{})
        password-label (if (= mode :edit) "New password" "Password")
        password-help (if (= mode :edit) "Leave blank to keep the current password." "Required for new users.")]
    [detail-shell {:title "User"
                   :new-label "New user"
                   :save-label (if (= mode :create) "Create user" "Save")
                   :mode mode
                   :status status
                   :error error
                   :saving? saving?
                   :deleting? deleting?
                   :has-id? (:id form)
                   :on-new #(rf/dispatch [:darelwasl.app/new-user])
                   :on-save #(rf/dispatch [:darelwasl.app/save-user])
                   :on-delete #(when (js/confirm "Delete this user? This cannot be undone.")
                                 (rf/dispatch [:darelwasl.app/delete-user]))
                   :disable-delete? is-self?}
     [:div.detail-grid
      [:div.field-group
       [:label "Username"]
       [ui/form-input {:value (:username form)
                       :on-change #(rf/dispatch [:darelwasl.app/set-user-field :username (.. % -target -value)])}]]
      [:div.field-group
       [:label "Display name"]
       [ui/form-input {:value (:name form)
                       :on-change #(rf/dispatch [:darelwasl.app/set-user-field :name (.. % -target -value)])}]]
      [:div.field-group
       [:label password-label]
       [ui/form-input {:type "password"
                       :value (:password form)
                       :on-change #(rf/dispatch [:darelwasl.app/set-user-field :password (.. % -target -value)])}]
       [:div.meta password-help]]
      [:div.field-group
       [:label "User ID"]
       [:div.meta (or (:user/id selected) "New user")]]]
     [role-picker roles]
     (when is-self?
       [:div.meta "You cannot delete the active user while signed in."])]))

(defn users-shell
  []
  [shell/app-shell
   [:main.users-layout
    [:div.users-grid
     [list-panel]
     [detail-panel]]]
   [:span "User management"]])
