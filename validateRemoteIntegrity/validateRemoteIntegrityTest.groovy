import spock.lang.Specification

import org.jfrog.artifactory.client.ArtifactoryClientBuilder
import org.jfrog.artifactory.client.ItemHandle
import org.jfrog.artifactory.client.model.repository.settings.impl.DebianRepositorySettingsImpl


class RemoteDownloadChecksumTest extends Specification {
    def artifactory
    def builder

    def createRemoteRepo(String repoKey, String repoUrl) {
        def remote = this.builder.remoteRepositoryBuilder()
            .key(repoKey)
            .url(repoUrl)
        remote.repositorySettings(new DebianRepositorySettingsImpl())
        artifactory.repositories().create(0, remote.build())
        return artifactory.repository(repoKey)
    }

    def getFileChecksumPropertiesSize(ItemHandle fileHandle) {
        return fileHandle.getProperties('header.checksum.md5',
                                        'header.checksum.crc32c',
                                        'calculated.checksum.md5',
                                        'calculated.checksum.crc32c')
                          .size()
    }

    def 'remote download checksum test'() {
        setup:
        def UBUNTU_REPO_KEY = 'remote-ubuntu-debian-checksum-test'
        def UBUNTU_TEST_FILE = '/pool/main/a/aalib/aalib_1.4p5-39ubuntu1.dsc'
        def GOOGLE_REPO_KEY = 'remote-bazel-apt-checksum-test'
        def GOOGLE_TEST_FILE = 'dists/stable/jdk1.7/binary-amd64/Packages.gz'
        def baseurl = 'http://localhost:8081/artifactory'
        artifactory = ArtifactoryClientBuilder.create().setUrl(baseurl)
            .setUsername('admin').setPassword('password').build()
        builder = artifactory.repositories().builders()

        def remoteUbuntuRepo = createRemoteRepo(UBUNTU_REPO_KEY, 'http://archive.ubuntu.com/ubuntu/')
        def remoteGoogleRepo = createRemoteRepo(GOOGLE_REPO_KEY, 'http://storage.googleapis.com/bazel-apt')

        when:
        remoteUbuntuRepo.download(UBUNTU_TEST_FILE).doDownload();

        then:
        getFileChecksumPropertiesSize(remoteUbuntuRepo.file(UBUNTU_TEST_FILE)) == 0

        when:
        remoteGoogleRepo.download(GOOGLE_TEST_FILE).doDownload();

        then:
        getFileChecksumPropertiesSize(remoteGoogleRepo.file(GOOGLE_TEST_FILE)) == 4

        cleanup:
        remoteUbuntuRepo.delete()
        remoteGoogleRepo.delete()
    }
}
