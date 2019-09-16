import org.artifactory.fs.FileInfo
import org.artifactory.repo.RepoPath
import org.artifactory.repo.Repositories
import org.artifactory.request.Request
import org.apache.commons.io.IOUtils
import java.util.zip.CRC32C
import groovy.json.JsonSlurper


class FetchRemoteUrl {
    public String remoteUrl
    public int responseCode
    private String responseContent
    private String packagePath
    private String ARTIFACTORY_STORAGE_API = "/api/storage/"

    public FetchRemoteUrl(String servletUrl, String localPath) {
        this.remoteUrl = null
        this.responseCode = 0
        this.packagePath = servletUrl + this.ARTIFACTORY_STORAGE_API + localPath
        this.findRemoteUrl()
    }

    private boolean fetchPackageInfo() {
        def request = new URL(this.packagePath).openConnection()
        request.doOutput = true
        request.setRequestMethod("GET")
        this.responseCode = request.getResponseCode()
        try {
            this.responseContent = request.getInputStream().getText()
            return true
        } catch (ex) {
            return false
        }
    }

    private void parseRemoteUrlFromPackageInfo() {
        def jsonSlurper = new JsonSlurper();
        try {
            def jsonResponse = jsonSlurper.parseText(this.responseContent)
            this.remoteUrl = jsonResponse.remoteUrl
        } catch (ex) {
            this.remoteUrl = null
        }
    }

    private void findRemoteUrl() {
        if(this.fetchPackageInfo()) {
            this.parseRemoteUrlFromPackageInfo()
        }
    }
}


class ChecksumCalc {
    private RepoPath repoPath
    private Repositories repositories

    public ChecksumCalc(Repositories repositories, RepoPath repoPath) {
        this.repositories = repositories
        this.repoPath = repoPath
    }

    public String crc32c() {
        CRC32C crc = new CRC32C()
        def content = this.repositories.getContent(this.repoPath)
        def bytesInput = IOUtils.toByteArray(content.inputStream)
        crc.update(bytesInput)
        def crc32cHex = Long.toHexString(crc.getValue())
        return ("00000000" + crc32cHex).substring(crc32cHex.length())
    }

    public String md5() {
        FileInfo fileInfo = this.repositories.getFileInfo(this.repoPath)
        return fileInfo.getMd5()
    }
}


class ChecksumValidator {
    private RepoPath repoPath
    private Repositories repositories
    private String remoteUrl
    private ChecksumCalc checksumCalc
    private Map<String,List<String>> headerChecksums
    private Map<String,List<String>> calculatedChecksums

    public ChecksumValidator(Repositories repositories, RepoPath repoPath, String remoteUrl) {
        this.repositories = repositories
        this.repoPath = repoPath
        this.remoteUrl = remoteUrl
        this.checksumCalc = new ChecksumCalc(this.repositories, this.repoPath)
        this.setHeaderChecksums()
        this.setCalculatedChecksums(this.headerChecksums.keySet())
    }

    public Map<String,List<String>> getHeaderChecksums() {
        return this.headerChecksums
    }
    public Map<String,List<String>> getCalculatedChecksums(def checksumTypes) {
        return this.calculatedChecksums
    }

    public Boolean areChecksumsCorrect() {
        return this.headerChecksums.equals(this.calculatedChecksums)
    }

    private Boolean isChecksumTypeSupported(String type) {
        return ChecksumCalc.metaClass.respondsTo(this.checksumCalc, type)
    }

    private void setHeaderChecksums() {
        this.headerChecksums = [:]
        def connection = new URL(this.remoteUrl).openConnection()
        def headerFields = connection.getHeaderFields()
        for (def headerEntry in headerFields) {
            for (def checksumEntry: headerEntry.value) {
                def indexOfEqls = checksumEntry.indexOf("=")
                if (indexOfEqls != -1) {
                    def checksumType = checksumEntry.take(indexOfEqls)
                    if (this.isChecksumTypeSupported(checksumType)) {
                        def checksumValue = checksumEntry.drop(indexOfEqls + 1)
                        def decodedChecksumValue = checksumValue.decodeBase64().encodeHex().toString()
                        this.headerChecksums[checksumType] = decodedChecksumValue
                    }
                }
            }
        }
    }

    private void setCalculatedChecksums(def checksumTypes) {
        this.calculatedChecksums = [:]
        for (def checksumType: checksumTypes) {
            if (this.isChecksumTypeSupported(checksumType)) {
                this.calculatedChecksums[checksumType] = this.checksumCalc."${checksumType}"()
            }
        }
    }
}


def setChecksumProperties(def repositories, def repoPath, def checksumSource, def checksums) {
    for (def checksum in checksums) {
        def propertyKey = checksumSource + ".checksum." + checksum.key
        repositories.setProperty(repoPath, propertyKey, checksum.value)
    }
}


download {

    afterRemoteDownload { Request request, RepoPath repoPath ->

        try {
            def servletUrl = request.getServletContextUrl()
            def packagePath = repoPath.toPath()
            def remoteUrlGetter = new FetchRemoteUrl(servletUrl, packagePath)

            if (remoteUrlGetter.remoteUrl == null) {
                log.error("HTTP error $remoteUrlGetter.responseCode : Unable to get remote url for package: $packagePath")
                return
            }

            def checksumValidator = new ChecksumValidator(repositories, repoPath, remoteUrlGetter.remoteUrl)
            def headerChecksums = checksumValidator.getHeaderChecksums()
            def calculatedChecksums = checksumValidator.getCalculatedChecksums(headerChecksums.keySet())
            setChecksumProperties(repositories, repoPath, "header", headerChecksums)
            setChecksumProperties(repositories, repoPath, "calculated", calculatedChecksums)

            if (checksumValidator.getHeaderChecksums()) {
                if (checksumValidator.areChecksumsCorrect()) {
                    log.info("All checksums are equal.")
                } else {
                    log.warn("Remote url: " + remoteUrlGetter.remoteUrl)
                    log.warn("Checksums not equal. File corrupted.")
                    log.warn("Header checksums: $headerChecksums")
                    log.warn("Calculated checksums: $calculatedChecksums")
                }
            } else {
                log.warn("Remote url: " + remoteUrlGetter.remoteUrl)
                log.warn("Header does not contain any supported checksums.")
            }

        } catch(ex) {
            log.warn("$ex")
        }
    }
}
