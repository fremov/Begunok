import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import OverviewView from './OverviewView.vue'
import './style.css'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'overview', component: OverviewView },
    { path: '/history', name: 'history', component: () => import('./HistoryView.vue') },
    { path: '/calendar', name: 'calendar', component: () => import('./CalendarView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: (to, from, savedPosition) => savedPosition ?? (to.name === from.name ? undefined : { top: 0 }),
})

createApp(App).use(router).mount('#app')
