import SwiftUI
import AppKit
struct NativeStatus: Decodable { let host:String; let backend:String; let engineReady:Bool; let residentBytes:Int; let virtualMachine:Bool }
struct Registration:Decodable {let registered:Bool;let accountDid:String?;let approvalUri:String?;let expiresAt:Double?}
struct ClusterFacts: Decodable {let registration:Registration?;let nativeAvailable:Bool;let gpuVerified:Bool;let engineReady:Bool;let backend:String?;let model:String?;let busy:Bool;let clientMiB:Int?;let freeMiB:Int;let totalMiB:Int}
struct ClusterNode: Decodable,Identifiable {var id:String{did};var admitted:Bool{reachable && proofVerified && observedAt != nil && Date().timeIntervalSince1970*1000-observedAt! < 120000};let name:String;let did:String;let reachable:Bool;let observedAt:Double?;let facts:ClusterFacts?;let proofVerified:Bool}
struct ClusterBot:Decodable {let name:String;let mode:String;let completedTicks:Int;let lastTick:Double?}
struct FleetConnection:Decodable,Identifiable {var id:String{name};let name:String;let state:String;let reason:String;let observers:Int;let observedAt:Double;let sshReady:Bool;let residentReachable:Bool;let proofVerified:Bool}
struct LocalModelCapacity:Decodable {let kind:String;let ready:Int;let free:Int;let busy:Int}
struct LocalFleetCapacity:Decodable {let ok:Bool;let models:[String:LocalModelCapacity]}
struct ClusterStatus:Decodable {let fleetNodes:[FleetConnection]?;let clusterId:String;let ownership:String;let nodes:[ClusterNode];let bot:ClusterBot}
@MainActor class StatusModel: ObservableObject {
 @Published var text="接続を確認しています…"
 @Published var cluster:ClusterStatus?
 @Published var clusterError:String?
 @Published var localFleet:LocalFleetCapacity?
 @Published var localFleetAt:Date?
 @Published var localFleetError:String?
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
 func refreshLocalFleet(){guard let url=URL(string:"https://api.murakumo.cloud/ready?model=mishima") else{return};var r=URLRequest(url:url);r.timeoutInterval=8;r.cachePolicy = .reloadIgnoringLocalCacheData;URLSession.shared.dataTask(with:r){data,response,_ in Task{@MainActor in guard let data=data,let status=(response as? HTTPURLResponse)?.statusCode,[200,503].contains(status),let f=try? JSONDecoder().decode(LocalFleetCapacity.self,from:data) else{self.localFleetError="ローカル推論クラスタの状態を取得できません";return};self.localFleet=f;self.localFleetAt=Date();self.localFleetError=nil}}.resume()}
 func refreshCluster(){refreshLocalFleet();request(19843,"/cluster/status"){data in Task{@MainActor in guard let data=data,let s=try? JSONDecoder().decode(ClusterStatus.self,from:data) else{self.clusterError="監視Botに接続できません。最後の表示は過去の観測です。";return};self.cluster=s;self.clusterError=nil}}}
}
struct ClusterView:View {
 @ObservedObject var model:StatusModel
 var body:some View{VStack(alignment:.leading,spacing:18){
  HStack{VStack(alignment:.leading){Text("Murakumo 全体地図").font(.largeTitle.bold());Text("Itonami · クラスタ監視・運用Bot").foregroundColor(.secondary)};Spacer();Button("更新"){model.refreshCluster()}}
  VStack(alignment:.leading,spacing:6){Text("api.murakumo.cloud · ローカル推論クラスタ").font(.headline);if model.localFleetError==nil,let at=model.localFleetAt,Date().timeIntervalSince(at)<30,let fleet=model.localFleet,let mishima=fleet.models["mishima"]{HStack{Text("Mishima: \(mishima.ready) 台 Ready / \(mishima.free) 台 空き / \(mishima.busy) 台 実行中").foregroundColor(fleet.ok ? .primary:.orange);Spacer();Text(at,style:.time).font(.caption)};Text("入力長により利用できる head は異なります。容量不足ではローカルの空きを待ち、OpenRouter は使いません。").font(.caption).foregroundColor(.secondary)}else{Text(model.localFleetError ?? "状態を確認中 · Unknown").foregroundColor(.orange)}}.padding(12).background(Color(NSColor.controlBackgroundColor)).cornerRadius(10)
  if let error=model.clusterError{Text(error).foregroundColor(.orange)}
  if let c=model.cluster{
   HStack{Label("\(c.nodes.filter{$0.admitted}.count) / \(c.nodes.count) 台の端末 Client を署名観測",systemImage:"network");Spacer();Text("監視 \(c.bot.completedTicks) 回")}
   Text("ユーザー連携: \(c.ownership == "server-registered" ? "登録窓口で全台の同一所有者を確認済み" : c.ownership == "multiple-owner-accounts" ? "複数の所有者があるため確認が必要" : "本人署名による登録待ち")").font(.callout).foregroundColor(.secondary)
   ScrollView{VStack(spacing:8){ForEach(c.nodes){n in HStack(spacing:16){Circle().fill(n.admitted ? Color.green:Color.orange).frame(width:9,height:9);VStack(alignment:.leading,spacing:4){Text(n.name).font(.headline);Text(n.admitted ? (n.facts?.model ?? "未観測") : "署名付きの新しい観測なし").font(.caption).foregroundColor(.secondary)}.frame(width:260,alignment:.leading);Spacer();VStack(alignment:.trailing,spacing:4){Text(n.admitted ? (n.facts?.engineReady == true ? "端末の推論エンジン稼働":"端末エンジン確認が必要"):"応答なし / 未観測");Text(n.proofVerified ? "端末署名を検証済み":"署名未確認").font(.caption).foregroundColor(.secondary)};Text(n.admitted && n.facts?.gpuVerified == true ? "Metal / GPU":"GPU未確認").frame(width:110);if n.admitted,let f=n.facts{Text("空き \(f.freeMiB/1024) / \(f.totalMiB/1024) GiB").font(.caption).frame(width:140)};if n.admitted,let r=n.facts?.registration,r.registered==false,let expires=r.expiresAt,expires>Date().timeIntervalSince1970*1000,let link=r.approvalUri,link.hasPrefix("https://auth.kotoba.cloud/node?code="),let url=URL(string:link){Button("連携"){NSWorkspace.shared.open(url)}};if let at=n.observedAt{Text(Date(timeIntervalSince1970:at/1000),style:.time).font(.caption).foregroundColor(.secondary)}}.padding(14).background(Color(NSColor.controlBackgroundColor)).cornerRadius(12)}}}
   if let fleet=c.fleetNodes,!fleet.isEmpty{Text("接続不良を監視しているノード").font(.headline);ForEach(fleet){n in HStack{Circle().fill(n.state == "Reachable" && Date().timeIntervalSince1970*1000-n.observedAt < 120000 ? Color.green:Color.orange).frame(width:9,height:9);Text(n.name).font(.headline).frame(width:110,alignment:.leading);VStack(alignment:.leading){Text(Date().timeIntervalSince1970*1000-n.observedAt < 120000 ? n.state:"Unknown · 観測期限切れ");Text(n.reason).font(.caption).foregroundColor(.secondary)};Spacer();Text("署名観測 \(n.observers) 台").font(.caption);Text(Date(timeIntervalSince1970:n.observedAt/1000),style:.time).font(.caption)}.padding(8)};Text("接続観測は推論の資格や所有者登録とは別です。電源・管理接続がないノードは現地対応を待ちます。").font(.caption).foregroundColor(.secondary)}
   Text("各端末が独立して監視を継続。署名付き状態と履歴を端末間で保持します。所有者認証・公開ジョブの権限は監視署名と別です。").font(.caption).foregroundColor(.secondary)
  }else if model.clusterError==nil{ProgressView("監視状態を取得しています…")}
  HStack{Button("Murakumo 管理画面"){NSWorkspace.shared.open(URL(string:"https://murakumo.cloud/portal/#devices")!)};Button("Itonami Bot管理"){NSWorkspace.shared.open(URL(string:"https://app.itonami.cloud/")!)}}
 }.padding(28).frame(minWidth:880,minHeight:520).onAppear{model.refreshCluster()}.onReceive(Timer.publish(every:10,on:.main,in:.common).autoconnect()){_ in model.refreshCluster()}}
}
struct ClusterMenu:View {
 @ObservedObject var model:StatusModel
 @Environment(\.openWindow) private var openWindow
 var body:some View {Text(model.text).onAppear{model.refresh()};Button("状態を更新"){model.refresh()};Button("クラスタを表示"){openWindow(id:"cluster");NSApplication.shared.activate(ignoringOtherApps:true)};Divider();Button("終了"){NSApplication.shared.terminate(nil)}}
}
@main struct MurakumoClientApp:App {
 @StateObject var model=StatusModel()
 var body:some Scene{
  MenuBarExtra("Murakumo",systemImage:"cloud"){ClusterMenu(model:model)}
  WindowGroup("Murakumo Cluster",id:"cluster"){ClusterView(model:model)}
 }
}
