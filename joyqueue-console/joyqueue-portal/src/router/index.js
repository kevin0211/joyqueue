import Vue from 'vue'
import Router from 'vue-router'
import store from '../store'
import i18n from '../i18n'
import { mergeDeep } from '../utils/assist'
import NavConfig from '../i18n/navs.json'

Vue.use(Router)

const originalPush = Router.prototype.push
Router.prototype.push = function (location) {
  return originalPush.call(this, location).catch(err => err)
}

// ------- 通过JSON配置动态生成路由 Begin ------- //
export function registerRoute (...extendConfigs) {
  const routes = []
  const parentRoutes = {}

  // 深度合并配置
  const navConfig = mergeDeep(NavConfig, ...extendConfigs)

  // 生成路由
  Object.keys(navConfig).forEach(lang => {
    const pageNavs = navConfig[lang]

    for (let nav of pageNavs) {
      const parentPath = nav.path
      const parentKey = `/${lang}${parentPath}`

      addParentRoute(parentKey, nav, lang)

      if (nav.children) {
        nav.children.forEach(child => {
          addRoute(parentKey, child, lang)
        })
      }
    }
  })

  function addParentRoute (parentKey, nav, lang) {
    if (parentRoutes[parentKey]) {
      return
    }
    let parentRoute = {
      path: parentKey,
      name: parentKey,
      children: []
    }
    if (nav.component && nav.component !== '') {
      parentRoute = {
        ...parentRoute,
        component: () => import(`../views/${nav.component}`)
      }
    }
    if (nav.name && nav.name !== '') {
      parentRoute = {
        ...parentRoute,
        meta: {title: `${nav.name}`}
      }
    }
    if (nav.redirect && nav.redirect !== '') {
      parentRoute = {
        ...parentRoute,
        redirect: `/${lang}${nav.redirect}`
      }
    }
    parentRoutes[parentKey] = parentRoute
    return parentRoute
  }

  function addRoute (parentKey, child, lang) {
    const parentRoute = parentRoutes[parentKey]
    const routePath = child.path
    const routeKey = routePath === '/'
      ? `${parentKey}/` : `${parentKey}/${routePath}`
    let childRoute = {
      path: routePath,
      name: routeKey
    }
    if (child.component && child.component !== '') {
      childRoute = {
        ...childRoute,
        component: () => import(`../views/${child.component}`)
      }
    }
    if (child.name && child.name !== '') {
      childRoute = {
        ...childRoute,
        meta: {title: `${child.name}`}
      }
    }
    if (child.redirect && child.redirect !== '') {
      childRoute = {
        ...childRoute,
        redirect: `/${lang}${child.redirect}`
      }
    }
    parentRoute.children.push(childRoute)
  }

  for (const key in parentRoutes) {
    if (parentRoutes.hasOwnProperty(key)) {
      routes.push(parentRoutes[key])
    }
  }

  return routes
}

let routes = registerRoute()

routes = routes.concat([{
  path: '/',
  redirect: {name: `/${i18n.locale}/application`}
}, {
  path: '/error',
  component: () => import('../views/404'),
  hidden: true,
  name: 'error'
}, {
  path: '*',
  redirect: '/error',
  hidden: true
}])

const router = new Router({
  routes,
  scrollBehavior (to, from, savedPosition) {
    if (to.hash) {
      return {
        selector: to.hash
      }
    }
    return { x: 0, y: 0 }
  }
})

// ------- 通过JSON配置动态生成路由 End ------- //

const whiteList = ['/noPermission']
const goNext = function (to, from, next, role) {
  if (to.meta.admin && role !== 'admin') {
    next({path: '/noPermission'})
  } else {
    next()
  }
}

router.beforeEach((to, from, next) => {
  if (whiteList.indexOf(to.path) !== -1) {
    next()
  } else {
    let loginUserName = store.getters.loginUserName
    let loginUserRole = store.getters.loginUserRole
    try {
      if (!loginUserName) { // 先判断是否已登录
        store.dispatch('getUserInfo').then(data => {
          loginUserName = store.getters.loginUserName
          loginUserRole = store.getters.loginUserRole

          if (loginUserName) { // 其次判断是否有权访问
            goNext(to, from, next, loginUserRole)
          } else {
            location.href = 'https://ssa.jd.com/sso/login?ReturnUrl' + window.location.href
          }
        })
      } else {
        goNext(to, from, next, loginUserRole)
      }
    } catch (err) {
      location.href = 'https://ssa.jd.com/sso/login?ReturnUrl' + window.location.href
    }
  }
})

export default router

export { routes, router }
