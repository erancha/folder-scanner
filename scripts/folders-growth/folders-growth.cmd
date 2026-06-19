set "EXCLUDE=Windows,ProgramData,Program Files,Program Files (x86),$Recycle.Bin,System Volume Information,workspaceStorage,extensions,.idea,.git,node_modules,target,.mvn,build,dist,.gradle,bin,EBWebView,WebviewCacheX64,ebview2_user_data,cef_cache,WidevineCdm,component_crx_cache,AmazonQ,puppeteer,.nuget,Adobe,AzureFunctionsTools,CSharpier,Windsurf,ws-browser,Postman-Agent,DBeaverData,Chrome,Old FirefoxData"

java -jar ../../target/folder-scanner-1.0-SNAPSHOT.jar --consumer=folders --exclude="%EXCLUDE%" "c:/" --min-size-recursive=100MB --baseline=folder-sizes

@REM java -jar ../../target/folder-scanner-1.0-SNAPSHOT.jar --consumer=filemanager --exclude="%EXCLUDE%" "c:/" --min-size=100MB --sort=date

pause