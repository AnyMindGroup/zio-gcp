import { defineConfig } from 'vitepress'
import { execSync } from 'child_process'

const version = execSync("git tag -l --sort=-v:refname")
  .toString().split("\n")
  .find(v => v.startsWith("v"))
  ?.slice(1) ?? "latest"

// https://vitepress.dev/reference/site-config
export default defineConfig({
  srcDir: "..",
  base: '/zio-gcp/',
  title: "ZIO GCP",
  description: "Collection of Google Cloud clients for ZIO",
  head: [
    ['link', { rel: 'icon', href: '/zio-gcp/favicon.png' }],
  ],

  themeConfig: {
    // https://vitepress.dev/reference/default-theme-config
    nav: [
      { text: 'Home', link: '/' },
      { text: 'Documentation', link: '/getting-started' }
    ],

    logo: '/zio_gcp.png',

    sidebar: {
      '/': [
        {
          text: 'Documentation',
          items: [
            { text: 'Getting started', link: '/getting-started' },
            { text: 'Authentication', link: '/authentication/' },
            { text: 'Adding new clients', link: '/add-new-clients' },
          ]
        },
        {
          text: 'Clients',
          items: [
            {
              text: 'Pub/Sub', link: '/pubsub/',
              items: [
                { text: 'Overview', link: '/pubsub/' },
                { text: 'Publisher', link: '/pubsub/publisher' },
                { text: 'Subscriber', link: '/pubsub/subscriber' },
                { text: 'Admin', link: '/pubsub/admin' },
                { text: 'Serializer / Deserializer', link: '/pubsub/serde' },
              ]
            },
            { text: 'Storage', link: '/storage/' },
            { text: 'AI platform', link: '/aiplatform/' },
            { text: 'Sheets', link: '/sheets/' },
            { text: 'BigQuery', link: '/bigquery/' },
          ]
        },
      ]
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/AnyMindGroup/zio-gcp' }
    ]
  },
  vite: {
    plugins: [{
      name: 'replace-version',
      transform(code, id) {
        if (id.endsWith('.md')) return code.replaceAll('@VERSION@', version)
      }
    }]
  },
})
