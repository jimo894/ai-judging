import { defineConfig } from '@playwright/test'
export default defineConfig({testDir:'./tests',timeout:60000,workers:1,reporter:[['list'],['html',{open:'never'}]],use:{baseURL:'http://127.0.0.1:8080',browserName:'chromium',channel:'msedge',headless:true,viewport:{width:1440,height:1000},screenshot:'only-on-failure',trace:'retain-on-failure'}})
