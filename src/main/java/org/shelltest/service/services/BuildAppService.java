package org.shelltest.service.services;

import org.jetbrains.annotations.NotNull;
import org.shelltest.service.dto.RollbackFrontendEntity;
import org.shelltest.service.dto.BuildEntity;
import org.shelltest.service.entity.History;
import org.shelltest.service.entity.Property;
import org.shelltest.service.exception.MyException;
import org.shelltest.service.mapper.HistoryMapper;
import org.shelltest.service.utils.Constant;
import org.shelltest.service.utils.DeployLogUtil;
import org.shelltest.service.utils.ShellRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class BuildAppService {

    @Autowired
    UploadService uploadService;
    @Autowired
    PropertyService propertyService;
    @Autowired
    HistoryMapper historyMapper;
    @Autowired
    DeployLogUtil deployUtil;

    @Value("${local.git.url}")
    String git_url;
    @Value("${local.git.username}")
    String git_user;
    @Value("${local.git.password}")
    String git_password;
    @Value("${local.path.git}")
    String localGitPath;
    @Value("${local.path.jar}")
    String jarPath;

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * 部署微服务.
     * @param remoteRunner 远程连接
     * @param localPath jar包在本机的路径
     * @param deployPath 要上传到远程路径
     * @param runPath 远程机器上jar包运行的路径
     * */
    public void deployService(ShellRunner remoteRunner, String localPath, String deployPath, String runPath) {
        History deployLog = deployUtil.createLogEntity(remoteRunner);
        StringBuffer deployResult = new StringBuffer();
        deployResult.append("部署类型：从文件部署后端\n");
        deployResult.append("目标服务器："+deployLog.getTarget()+"\n");
        try {
            uploadService.uploadFiles(remoteRunner, localPath, deployPath, "jar");
            deployResult.append("上传所有jar包成功\n");
            // todo 写脚本，拼接脚本参数
            // 可用的脚本在云内放着，到时候再看着简化一下写在这里
            // todo 预计要再使用一张表存储不同服务器上的启动配置
            if (remoteRunner.runCommand("sh DeployService.sh"+ShellRunner.appendArgs(new String[]{}))) {
                deployResult.append("部署成功\n");
            } else {
                deployResult.append("部署脚本执行异常\n");
            }
            deployResult.append("错误信息：\n"+remoteRunner.getError());
        } catch (MyException e) {
            deployResult.append("部署错误："+e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            deployResult.append("意外的Exception"+e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                remoteRunner.runCommand("rm -f DeployService.sh");
                remoteRunner.runCommand("rm -f StartService.sh");
                remoteRunner.exit();
            } catch (MyException e){e.printStackTrace();}
        }
    }

    /**
     * 回滚前端.
     * 因为回滚比较快，所以直接返回
     * 复制文件可能会用一些时间，一个前端包的赋值应该不会用太长时间
     * */
    public void rollbackFrontend(ShellRunner remoteRunner, List<Property> serverInfoList, @NotNull List<RollbackFrontendEntity> rollbackList) throws MyException {
        History deployLog = deployUtil.createLogEntity(remoteRunner);
        logger.info("开始回滚："+deployLog.getTarget());
        StringBuffer deployResult = new StringBuffer();
        deployResult.append("部署类型：从备份回滚前端\n");
        deployResult.append("目标服务器："+deployLog.getTarget()+"\n");
        deployResult.append("所有回滚：\n");
        for (int i = 0; i < rollbackList.size(); i++) {
            RollbackFrontendEntity dataDTO = rollbackList.get(i);
            // @param 2.备份父文件夹
            // @param 3.父文件夹下的以时间作名字的具体备份文件夹
            // @param 4.要部署到webapps文件夹底下的文件夹名，一般来讲与备份父文件夹同名，不排除需要自定义的场景
            String tarDir = (dataDTO.getTarDir() == null || "".equalsIgnoreCase(dataDTO.getTarDir()))?dataDTO.getName():dataDTO.getTarDir();
            String backupPath = propertyService.getPropertyValueByType(serverInfoList, Constant.PropertyType.BACKUP_PATH);
            if (remoteRunner.runCommand("sh RollbackFrontend.sh" +
                    ShellRunner.appendArgs(new String[]{backupPath, dataDTO.getName(), dataDTO.getTarBackup(), tarDir}) )) {
                deployResult.append("回滚["+dataDTO.getName()+"/"+dataDTO.getTarBackup()+"]到【"+tarDir+"】成功\n");
            } else {
                deployResult.append("回滚["+dataDTO.getName()+"/"+dataDTO.getTarBackup()+"]到【"+tarDir+"】异常\n");
            }
            deployResult.append("错误信息："+remoteRunner.getError());
        }
        //3.全工程clear，写日志到数据库
        deployLog.setEndTime(new Date());
        deployLog.setResult(deployResult.toString());
        historyMapper.insertSelective(deployLog);
        deployResult.append("--- 回滚完成，已存储记录 ---");
    }

    /**
     * 打包前端列表.
     * 因为打包时间过长，故使用线程
     * 后台运行线程的方法不应该抛异常，抛出来给谁看？
     * @param localRunner 本机连接
     * @param serverInfoList 要部署的服务器的配置列表
     * @param deployList 要部署的应用列表
     */
    public void buildFrontendThread(ShellRunner localRunner, List<Property> serverInfoList,  BuildEntity[] deployList) {
        // 记录犯罪证据.jpg
        History deployLog = deployUtil.createLogEntity(serverInfoList.get(0).getKey());
        new Thread(()->{
            StringBuffer deployResult = new StringBuffer();
            ShellRunner remoteRunner = null;
            try {
                logger.info("--- 处理打包请求 ---");
                deployResult.append("部署类型：从Git部署前端\n");
                deployResult.append("目标服务器："+deployLog.getTarget()+"\n");
                deployResult.append("所有打包：\n");
                //在本机运行shell进行打包
                for (int i = 0; i < deployList.length; i++) {
                    deployResult.append("【"+(i+1)+"】"+deployList[i].getRepo()+":"
                            +deployList[i].getBranch()+":"+deployList[i].getFilename()+"\n");
                    String buildInfo = buildFrontend(localRunner, deployList[i].getRepo(), deployList[i].getBranch(),
                            deployList[i].getScript(), deployList[i].getFilename());
                    deployResult.append("打包结果：" + buildInfo + "\n");
                }
                //打包完成，不需要在本地再执行shell，退出这层远程登录，并登录远程和服务器
                localRunner.runCommand("rm -f BuildFrontend.sh");
                // localRunner.runCommand("rm -f LoginAuth.sh");
                localRunner.exit();

                //1.整理打包结果
                //确认可以登录远程服务器
                remoteRunner = new ShellRunner(deployLog.getTarget(), propertyService.getPropertyValueByType(serverInfoList, Constant.PropertyType.USERNAME),
                        propertyService.getPropertyValueByType(serverInfoList,Constant.PropertyType.PASSWORD));
                remoteRunner.login();
                //打包完成，上传包到远程服务器
                uploadService.uploadFiles(remoteRunner, localGitPath,
                        propertyService.getPropertyValueByType(propertyService.getServerInfo(deployLog.getTarget()),Constant.PropertyType.DEPLOY_PATH),"tar.gz");
                //上传完成，在远程服务器进行部署
                uploadService.uploadScript(remoteRunner, "DeployFrontend.sh", "frontend");
                //2.整理部署结果
                String args = ShellRunner.appendArgs(new String[]{
                        propertyService.getPropertyValueByType(serverInfoList,Constant.PropertyType.DEPLOY_PATH),
                        propertyService.getPropertyValueByType(serverInfoList,Constant.PropertyType.BACKUP_PATH)});
                if (remoteRunner.runCommand("sh DeployFrontend.sh"+args)) {
                    deployResult.append("部署成功 ");
                } else {
                    deployResult.append("部署异常 ");
                }
                if (remoteRunner.getError() != null)
                deployResult.append("错误信息:\n"+remoteRunner.getError()+"\n");
            } catch (MyException e) {
                logger.error(e.getMessage());
                deployResult.append("部署错误！\n"+e.getMessage());
            } finally {
                if (remoteRunner != null) {
                    try {
                        remoteRunner.runCommand("rm -f DeployFrontend.sh");
                        remoteRunner.exit();
                    } catch (MyException e) {
                        e.printStackTrace();
                    }
                }
            }
            //3.全工程clear，写日志到数据库
            deployLog.setEndTime(new Date());
            deployLog.setResult(deployResult.toString());
            historyMapper.insertSelective(deployLog);
            deployResult.append("--- 部署完成，已存储记录 ---");
            // emailService.sendSimpleEmail(phone,serverIP+"部署结束", deployResult.toString());
        }).start();
    }

    /**
     * 打包单个前端.
     * @param shellRunner 本地连接
     * @param gitRepository 要打包的git仓库
     * @param gitBranch 要checkout到的git分支
     * @param npmScript 打包运行的脚本
     * @param filename 打包后的dist要打包成的文件名：filename.tar.gz --(解压)--> filename/index.html
     * @return 打包信息，是否打包成功
     * */
    public String buildFrontend(@NotNull ShellRunner shellRunner, String gitRepository, String gitBranch, String npmScript, String filename) throws MyException {
        logger.debug("前端："+gitRepository+" 打包中");
        if (!isPacking(shellRunner, gitRepository)) {
            shellRunner.runCommand("sh BuildFrontend.sh"
                    + ShellRunner.appendArgs(new String[]{git_url,git_user,git_password,localGitPath,gitRepository,gitBranch,npmScript,filename}),null);
            logger.debug("前端："+gitRepository+" 打包完成");
            if (shellRunner.isSuccess())
                return "打包成功\n"+shellRunner.getError();
            else
                return "打包异常\n"+shellRunner.getError();
        } else {
            return "打包异常:"+gitRepository+"正在打包，请稍后重试";
        }
    }

    /**
     * 检查目录下是否有在进行打包.
     * 检查目录下是否有node或maven进程
     * @param shellRunner 远程登录连接
     * @param gitRepository git仓库文件夹
     * @return 该目录下是否有活跃的打包进程
     * */
    public boolean isPacking(@NotNull ShellRunner shellRunner, String gitRepository) throws MyException {
        shellRunner.runCommand("ps -ef|grep [n]ode|grep '"+gitRepository+"' | wc -l");
        String retNode = (shellRunner.getResult()==null || shellRunner.getResult().size() == 0)?"0":(shellRunner.getResult().get(0));

        shellRunner.runCommand("ps -ef|grep [m]vn|grep '"+gitRepository+"' | wc -l");
        String retMaven = (shellRunner.getResult()==null || shellRunner.getResult().size() == 0)?"0":(shellRunner.getResult().get(0));
        logger.info("maven进程运行数量 in "+gitRepository+" ： "+retMaven);

        return !(retNode.equalsIgnoreCase("0") && retMaven.equalsIgnoreCase("0"));
    }
}
