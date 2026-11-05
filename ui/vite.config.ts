import { viteConfig } from '@halo-dev/ui-plugin-bundler-kit/vite'
import Icons from 'unplugin-icons/vite'

export default viteConfig({
  vite: {
    plugins: [Icons({ compiler: 'vue3' })],
  },
})
