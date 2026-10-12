import SwiftUI
import AppKit
struct NativeStatus: Decodable { let host:String; let backend:String; let engineReady:Bool; let residentBytes:Int; let virtualMachine:Bool }
struct Registration:Decodable {let registered:Bool;let accountDid:String?;let approvalUri:String?;let expiresAt:Double?}
struct ClusterFacts: Decodable {let registration:Registration?;let nativeAvailable:Bool;let gpuVerified:Bool;let engineReady:Bool;let backend:String?;let model:String?;let busy:Bool;let clientMiB:Int?;let freeMiB:Int;let totalMiB:Int}
struct ClusterNode: Decodable,Identifiable {var id:String{did};let name:String;let did:String;let reachable:Bool;let observedAt:Double?;let facts:ClusterFacts?;let proofVerified:Bool}
struct ClusterBot:Decodable {let name:String;let mode:String;let completedTicks:Int;let lastTick:Double?}
struct ClusterStatus:Decodable {let clusterId:String;let ownership:String;let nodes:[ClusterNode];let bot:ClusterBot}
@MainActor class StatusModel: ObservableObject {
 @Published var text="接続を確認しています…"
 @Published var cluster:ClusterStatus?
 @Published var clusterError:String?
 func request(_ port:Int,_ path:String,completion:@escaping (Data?)->Void) {
  do {
   let root=FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("github/workspaces/murakumo/native-client")
   let c=try JSONSerialization.jsonObject(with:Data(contentsOf:root.appendingPathComponent("config.json"))) as? [String:Any]
   guard let tokenFile=c?["tokenFile"] as? String,let url=URL(string:"http://127.0.0.1:\(port)\(path)") else{completion(nil);return}
   let token=try String(contentsOfFile:tokenFile,encoding:.utf8).trimmingCharacters(in:.whitespacesAndNewlines)
   var r=URLRequest(url:url);r.setValue("Bearer "+token,forHTTPHeaderField:"Authorization");r.timeoutInterval=8
   URLSession.shared.dataTask(with:r){ data,response,_ in completion((response as? HTTPURLResponse)?.statusCode==200 ? data:nil)}.resume()
  }catch{completion(nil)}
 }
 func refresh(){request(19840,"/status"){data in Task{@MainActor in guard let data=data,let s=try? JSONDecoder().decode(NativeStatus.self,from:data) else{self.text="Clientに接続できません";return};self.text="\(s.host)\n\(s.backend)\n推論エンジン: \(s.engineReady ? "利用可能":"準備中")\nClient: \(s.residentBytes/1048576) MiB\nmacOS ネイティブ"}}}
 func refreshCluster(){request(19843,"/cluster/status"){data in Task{@MainActor in guard let data=data,let s=try? JSONDecoder().decode(ClusterStatus.self,from:data) else{self.clusterError="監視Botに接続できません。最後の表示は過去の観測です。";return};self.cluster=s;self.clusterError=nil}}}
}
struct ClusterView:View {
 @ObservedObject var model:StatusModel
 var body:some View{VStack(alignment:.leading,spacing:18){
  HStack{VStack(alignment:.leading){Text("Murakumo Cluster").font(.largeTitle.bold());Text("Itonami · クラスタ監視・運用Bot").foregroundColor(.secondary)};Spacer();Button("更新"){model.refreshCluster()}}
  if let error=model.clusterError{Text(error).foregroundColor(.orange)}
  if let c=model.cluster{
   HStack{Label("\(c.nodes.filter{$0.reachable}.count) / \(c.nodes.count) 台を観測",systemImage:"network");Spacer();Text("監視 \(c.bot.completedTicks) 回")}
   Text("ユーザー連携: \(c.ownership == "server-registered" ? "登録窓口で全台の同一所有者を確認済み" : c.ownership == "multiple-owner-accounts" ? "複数の所有者があるため確認が必要" : "本人署名による登録待ち")").font(.callout).foregroundColor(.secondary)
   ScrollView{VStack(spacing:8){ForEach(c.nodes){n in HStack(spacing:16){Circle().fill(n.reachable ? Color.green:Color.orange).frame(width:9,height:9);VStack(alignment:.leading,spacing:4){Text(n.name).font(.headline);Text(n.facts?.model ?? "未観測").font(.caption).foregroundColor(.secondary)}.frame(width:260,alignment:.leading);Spacer();VStack(alignment:.trailing,spacing:4){Text(n.reachable ? (n.facts?.engineReady == true ? "推論エンジン稼働":"エンジン確認が必要"):"応答なし / 未観測");Text(n.proofVerified ? "端末署名を検証済み":"署名未確認").font(.caption).foregroundColor(.secondary)};Text(n.facts?.gpuVerified == true ? "Metal / GPU":"GPU未確認").frame(width:110);if let f=n.facts{Text("空き \(f.freeMiB/1024) / \(f.totalMiB/1024) GiB").font(.caption).frame(width:140)};if let r=n.facts?.registration,r.registered==false,let link=r.approvalUri,link.hasPrefix("https://auth.kotoba.cloud/node?code="),let url=URL(string:link){Button("連携"){NSWorkspace.shared.open(url)}};if let at=n.observedAt{Text(Date(timeIntervalSince1970:at/1000),style:.time).font(.caption).foregroundColor(.secondary)}}.padding(14).background(Color(NSColor.controlBackgroundColor)).cornerRadius(12)}}}
   Text("各端末が独立して監視を継続。署名付き状態と履歴を端末間で保持します。所有者認証・公開ジョブの権限は監視署名と別です。").font(.caption).foregroundColor(.secondary)
  }else if model.clusterError==nil{ProgressView("監視状態を取得しています…")}
  HStack{Button("Murakumo 管理画面"){NSWorkspace.shared.open(URL(string:"https://murakumo.cloud/portal/#devices")!)};Button("Itonami Bot管理"){NSWorkspace.shared.open(URL(string:"https://app.itonami.cloud/")!)}}
 }.padding(28).frame(minWidth:880,minHeight:520).onAppear{model.refreshCluster()}.onReceive(Timer.publish(every:10,on:.main,in:.common).autoconnect()){_ in model.refreshCluster()}}
}
@main struct MurakumoClientApp:App {
 @StateObject var model=StatusModel()
 var body:some Scene{
  MenuBarExtra("Murakumo",systemImage:"cloud"){Text(model.text).onAppear{model.refresh()};Button("状態を更新"){model.refresh()};Button("クラスタを表示"){NSApplication.shared.activate(ignoringOtherApps:true);NSApp.windows.first{$0.title=="Murakumo Cluster"}?.makeKeyAndOrderFront(nil)};Divider();Button("終了"){NSApplication.shared.terminate(nil)}}
  WindowGroup("Murakumo Cluster"){ClusterView(model:model)}
 }
}
