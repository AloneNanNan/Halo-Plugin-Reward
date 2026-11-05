import { definePlugin } from "@halo-dev/ui-shared";
import IconHandCoins from "~icons/lucide/hand-coins";
import { markRaw } from "vue";
import RewardView from "./views/RewardView.vue";

export default definePlugin({
  routes: [
    {
      parentName: "Root",
      route: {
        path: "/reward",
        name: "Reward",
        component: RewardView,
        meta: {
          title: "打赏",
          searchable: true,
          permissions: ["plugin:plugin-reward:records:view"],
          menu: {
            name: "打赏",
            group: "content",
            icon: markRaw(IconHandCoins),
            priority: 30,
          },
        },
      },
    },
  ],
  extensionPoints: {},
});
