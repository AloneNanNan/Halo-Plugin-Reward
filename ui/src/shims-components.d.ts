export {};

declare module "vue" {
  export interface GlobalComponents {
    // Halo 全局注册的业务组件
    FilterDropdown: any;
    FilterCleanButton: any;
    SearchInput: any;
  }
}
